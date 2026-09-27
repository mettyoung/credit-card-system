package com.mettyoung.creditcardapplication.application.internal

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mettyoung.creditcardapplication.application.ApplicationStatus
import com.mettyoung.creditcardapplication.application.CardProduct
import io.kotest.core.annotation.Condition
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * The API end to end: real controller, service, aggregate, Hibernate and Postgres, driven over HTTP. Nothing is
 * mocked, so every assertion below is about behaviour a client can actually observe.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Kotest has no @Testcontainers equivalent, so the container starts with the class and the whole spec is
// skipped when Docker is missing — what disabledWithoutDocker did for the JUnit version.
@EnabledIf(DockerAvailable::class)
class ApplicationControllerTest : BehaviorSpec() {

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        init {
            if (dockerIsAvailable()) {
                postgres.start()
            }
        }
    }

    @Autowired
    private lateinit var mvc: MockMvc

    // Only to assert what the database ended up holding; every action goes through HTTP.
    @Autowired
    private lateinit var repository: ApplicationRepository

    // A fresh spec instance per test, so each test gets its own user below and never sees another's rows.
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val user = "u_" + UUID.randomUUID()

    init {
        extension(SpringExtension())

        // The declared-data form is saved as a whole, so every PATCH sends all four fields. Naming them
        // once here keeps each case about the one field it is exercising.
        fun form(
            firstName: String? = "Jane",
            lastName: String? = "Tan",
            dateOfBirth: String? = "1990-04-12",
            country: String? = "SG",
            version: String? = "0",
        ) = listOfNotNull(
            firstName?.let { """"firstName":"$it"""" },
            lastName?.let { """"lastName":"$it"""" },
            dateOfBirth?.let { """"dateOfBirth":"$it"""" },
            country?.let { """"country":"$it"""" },
            version?.let { """"version":$it""" },
        ).joinToString(",", "{", "}")

        Given("an applicant with no application yet") {

            When("they create one for a product") {
                val created = mvc.perform(json(post(APPLICATIONS), """{"cardProductCode":"PLATINUM"}"""))
                    .andExpect(status().isCreated)
                    .andReturn()
                val id = created.id()

                Then("the draft comes back at version 0, with its location") {
                    created.response.getHeader("Location") shouldBe "$APPLICATIONS/$id"
                    created.response.getHeader("ETag").shouldBeNull()
                    created.body() shouldContain """"cardProductCode":"PLATINUM""""
                    created.body() shouldContain """"status":"DRAFT""""
                    created.body() shouldContain """"firstName":null"""
                    created.body() shouldContain """"lastName":null"""
                    created.body() shouldContain """"dateOfBirth":null"""
                    created.body() shouldContain """"country":null"""
                    created.body() shouldContain """"version":0"""
                }

                Then("it is stored, owned by them, with no declared data yet") {
                    val stored = repository.findById(id).orElseThrow()

                    stored.userId shouldBe user
                    stored.cardProductCode shouldBe CardProduct.PLATINUM
                    stored.status shouldBe ApplicationStatus.DRAFT
                    stored.firstName.shouldBeNull()
                    stored.lastName.shouldBeNull()
                    stored.dateOfBirth.shouldBeNull()
                    stored.country.shouldBeNull()
                    stored.version shouldBe 0
                }
            }
        }

        Given("an applicant who already has a draft for a product") {
            val existing = mvc.perform(json(post(APPLICATIONS), """{"cardProductCode":"CLASSIC"}"""))
                .andExpect(status().isCreated)
                .andReturn()
                .id()

            When("they create the same product again") {
                Then("they get a conflict naming the draft they already have") {
                    mvc.perform(json(post(APPLICATIONS), """{"cardProductCode":"CLASSIC"}"""))
                        .andExpect(status().isConflict)
                        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("/problems/draft-already-exists"))
                        .andExpect(jsonPath("$.applicationId").value(existing.toString()))
                }

                Then("no second row is written") {
                    repository.listFor(user, null) shouldHaveSize 1
                }
            }

            When("they create a different product, and another applicant creates the same one") {
                Then("both are allowed: the rule is one draft per user per product") {
                    mvc.perform(json(post(APPLICATIONS), """{"cardProductCode":"PLATINUM"}"""))
                        .andExpect(status().isCreated)
                    mvc.perform(asUser("other_$user", post(APPLICATIONS), """{"cardProductCode":"CLASSIC"}"""))
                        .andExpect(status().isCreated)
                }
            }
        }

        Given("ten clients creating the same product at once") {

            When("the requests run concurrently") {
                val statuses = runConcurrently(10) {
                    mvc.perform(json(post(APPLICATIONS), """{"cardProductCode":"CLASSIC"}"""))
                        .andReturn().response.status
                }

                Then("exactly one is created and the rest are told a draft exists") {
                    statuses.count { it == 201 } shouldBe 1
                    statuses.count { it == 409 } shouldBe 9
                }

                Then("the unique index left exactly one row") {
                    repository.listFor(user, null) shouldHaveSize 1
                }
            }
        }

        Given("a create request the API can't act on") {

            When("the product is missing") {
                Then("it is rejected and nothing is written") {
                    mvc.perform(json(post(APPLICATIONS), "{}"))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.type").value("/problems/invalid-request"))
                        .andExpect(jsonPath("$.errors[0].field").value("cardProductCode"))
                    repository.listFor(user, null) shouldHaveSize 0
                }
            }

            When("the product is not one we offer") {
                Then("the offending field is named") {
                    mvc.perform(json(post(APPLICATIONS), """{"cardProductCode":"GOLD"}"""))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.errors[0].field").value("cardProductCode"))
                }
            }

            When("the body carries a misspelled field") {
                Then("the typo is reported instead of being ignored") {
                    mvc.perform(json(post(APPLICATIONS), """{"cardProductCode":"CLASSIC","fulName":"x"}"""))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.errors[0].field").value("fulName"))
                }
            }

            When("the body is not JSON at all") {
                Then("it is an invalid request") {
                    mvc.perform(json(post(APPLICATIONS), "{not json"))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.type").value("/problems/invalid-request"))
                }
            }
        }

        Given("a caller who does not identify themselves") {

            When("the user header is absent") {
                Then("the request is unauthorized") {
                    mvc.perform(
                        post(APPLICATIONS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"cardProductCode":"CLASSIC"}"""),
                    )
                        .andExpect(status().isUnauthorized)
                        .andExpect(jsonPath("$.type").value("/problems/unauthorized"))
                }
            }

            When("the user header is blank") {
                Then("the request is unauthorized") {
                    mvc.perform(get(APPLICATIONS).header(USER_HEADER, "   "))
                        .andExpect(status().isUnauthorized)
                }
            }
        }

        Given("a draft the applicant last read at version 0") {
            val id = mvc.perform(json(post(APPLICATIONS), """{"cardProductCode":"CLASSIC"}"""))
                .andExpect(status().isCreated)
                .andReturn()
                .id()

            When("they save the form with padded values and a lower-case country") {
                // The action belongs here: with InstancePerTest each Then replays its containers, so a side
                // effect left inside one Then would be missing from its siblings.
                val response = mvc.perform(
                    json(patch("$APPLICATIONS/$id"), form(firstName = "  Jane ", country = "sg")),
                )

                Then("the normalised values come back at the next version") {
                    response
                        .andExpect(status().isOk)
                        .andExpect(header().doesNotExist("ETag"))
                        .andExpect(jsonPath("$.firstName").value("Jane"))
                        .andExpect(jsonPath("$.lastName").value("Tan"))
                        .andExpect(jsonPath("$.dateOfBirth").value("1990-04-12"))
                        .andExpect(jsonPath("$.country").value("SG"))
                        .andExpect(jsonPath("$.version").value(1))
                }

                Then("the stored values are the value objects, not the raw input") {
                    val stored = repository.findById(id).orElseThrow()

                    stored.firstName shouldBe FirstName("Jane")
                    stored.lastName shouldBe LastName("Tan")
                    stored.dateOfBirth shouldBe DateOfBirth(LocalDate.of(1990, 4, 12))
                    stored.country shouldBe Country("SG")
                }
            }

            When("they send a blank first name") {
                val response = mvc.perform(json(patch("$APPLICATIONS/$id"), form(firstName = "   ")))

                Then("it answers in the same shape as a request-shape failure, naming the field") {
                    response
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("/problems/invalid-request"))
                        .andExpect(jsonPath("$.detail").value("firstName must not be blank."))
                        .andExpect(jsonPath("$.errors[0].field").value("firstName"))
                        .andExpect(jsonPath("$.errors[0].message").value("firstName must not be blank."))
                }

                Then("the row is untouched, so no field was half-applied") {
                    val stored = repository.findById(id).orElseThrow()

                    stored.firstName.shouldBeNull()
                    stored.lastName.shouldBeNull()
                    stored.dateOfBirth.shouldBeNull()
                    stored.country.shouldBeNull()
                    stored.version shouldBe 0
                }
            }

            When("they send a date of birth in the future") {
                val tomorrow = LocalDate.now().plusDays(1).toString()

                Then("the domain names that field") {
                    mvc.perform(json(patch("$APPLICATIONS/$id"), form(dateOfBirth = tomorrow)))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.errors[0].field").value("dateOfBirth"))
                        .andExpect(jsonPath("$.errors[0].message").value("dateOfBirth must be in the past."))
                    repository.findById(id).orElseThrow().firstName.shouldBeNull()
                }
            }

            When("they send a country that is not an ISO code") {
                Then("the domain names that field") {
                    mvc.perform(json(patch("$APPLICATIONS/$id"), form(country = "XX")))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.errors[0].field").value("country"))
                        .andExpect(
                            jsonPath("$.errors[0].message")
                                .value("country must be an ISO 3166-1 alpha-2 code."),
                        )
                    repository.findById(id).orElseThrow().country.shouldBeNull()
                }
            }

            When("they send a date that is not a date at all") {
                Then("Jackson refuses it before the domain, in the same shape") {
                    mvc.perform(json(patch("$APPLICATIONS/$id"), form(dateOfBirth = "12-04-1990")))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.type").value("/problems/invalid-request"))
                        .andExpect(jsonPath("$.errors[0].field").value("dateOfBirth"))
                }
            }

            When("they omit the version") {
                Then("the write is refused before it reaches the domain") {
                    mvc.perform(json(patch("$APPLICATIONS/$id"), form(version = null)))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.errors[0].field").value("version"))
                    repository.findById(id).orElseThrow().firstName.shouldBeNull()
                }
            }

            When("they send an empty body") {
                Then("bean validation names the required field") {
                    mvc.perform(json(patch("$APPLICATIONS/$id"), "{}"))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.type").value("/problems/invalid-request"))
                        .andExpect(jsonPath("$.errors[0].field").value("version"))
                        .andExpect(jsonPath("$.errors[0].message").value("Required."))
                }
            }

            When("they send a version that is not a number") {
                Then("the write is refused") {
                    mvc.perform(json(patch("$APPLICATIONS/$id"), form(version = "\"abc\"")))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.errors[0].field").value("version"))
                }
            }

            When("the form is saved and a stale copy tries to overwrite it") {
                mvc.perform(json(patch("$APPLICATIONS/$id"), form()))
                    .andExpect(status().isOk)

                Then("the stale write is refused with the version to reload") {
                    mvc.perform(json(patch("$APPLICATIONS/$id"), form(firstName = "John", lastName = "Lim")))
                        .andExpect(status().isConflict)
                        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.type").value("/problems/version-mismatch"))
                        .andExpect(jsonPath("$.currentVersion").value(1))
                }

                Then("the row still holds the values that won") {
                    val stored = repository.findById(id).orElseThrow()

                    stored.firstName shouldBe FirstName("Jane")
                    stored.lastName shouldBe LastName("Tan")
                    stored.version shouldBe 1
                }
            }

            When("the same form is written again at the current version") {
                mvc.perform(json(patch("$APPLICATIONS/$id"), form()))
                    .andExpect(status().isOk)

                Then("nothing is written and the version stays put") {
                    mvc.perform(json(patch("$APPLICATIONS/$id"), form(version = "1")))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.version").value(1))
                    repository.findById(id).orElseThrow().version shouldBe 1
                }
            }

            When("two clients update it with the same version at once") {
                val statuses = runConcurrently(2) { i ->
                    val firstName = listOf("Jane", "John")[i]
                    mvc.perform(json(patch("$APPLICATIONS/$id"), form(firstName = firstName)))
                        .andReturn().response.status
                }

                Then("exactly one wins and the other is told to reload") {
                    statuses.count { it == 200 } shouldBe 1
                    statuses.count { it == 409 } shouldBe 1
                }

                Then("the row moved exactly one version") {
                    repository.findById(id).orElseThrow().version shouldBe 1
                }
            }

            When("the applicant saves the form over HTTP") {
                val distinctiveName = "Zyxwvutprivacyname"
                val logged = captureLogs {
                    mvc.perform(json(patch("$APPLICATIONS/$id"), form(lastName = distinctiveName)))
                        .andExpect(status().isOk)
                }

                Then("the declared data never reaches the logs") {
                    logged shouldNotContain distinctiveName
                    logged shouldNotContain "1990-04-12"
                }
            }
        }

        Given("applications belonging to the caller and to somebody else") {
            val older = mvc.perform(json(post(APPLICATIONS), """{"cardProductCode":"CLASSIC"}"""))
                .andReturn().id()
            Thread.sleep(2) // UUIDv7 ordering is only guaranteed across milliseconds
            val newer = mvc.perform(json(post(APPLICATIONS), """{"cardProductCode":"PLATINUM"}"""))
                .andReturn().id()
            mvc.perform(asUser("other_$user", post(APPLICATIONS), """{"cardProductCode":"CLASSIC"}"""))
                .andExpect(status().isCreated)

            When("they read one by id") {
                Then("it comes back with its version") {
                    mvc.perform(get("$APPLICATIONS/$older").header(USER_HEADER, user))
                        .andExpect(status().isOk)
                        .andExpect(header().doesNotExist("ETag"))
                        .andExpect(jsonPath("$.id").value(older.toString()))
                        .andExpect(jsonPath("$.version").value(0))
                }
            }

            When("they list their own") {
                Then("they see only theirs, newest first") {
                    mvc.perform(get(APPLICATIONS).header(USER_HEADER, user))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.items.length()").value(2))
                        .andExpect(jsonPath("$.items[0].id").value(newer.toString()))
                        .andExpect(jsonPath("$.items[1].id").value(older.toString()))
                }

                Then("filtering by status keeps both drafts") {
                    mvc.perform(get(APPLICATIONS).param("status", "DRAFT").header(USER_HEADER, user))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.items.length()").value(2))
                }

                // A filter that excludes something has no test yet, and cannot have one: DRAFT is the only
                // status FR1 has, so every row matches whatever is asked for. FR4 adds the states that make
                // the predicate observable, and adds that case with them.

                Then("a status we don't have is rejected") {
                    mvc.perform(get(APPLICATIONS).param("status", "NOPE").header(USER_HEADER, user))
                        .andExpect(status().isUnprocessableContent)
                        .andExpect(jsonPath("$.errors[0].field").value("status"))
                }
            }

            When("somebody else tries to read or write one of them") {
                val intruder = "intruder_$user"

                Then("both are refused as not found, so ids can't be probed") {
                    mvc.perform(get("$APPLICATIONS/$older").header(USER_HEADER, intruder))
                        .andExpect(status().isNotFound)
                        .andExpect(jsonPath("$.type").value("/problems/not-found"))
                    mvc.perform(
                        asUser(intruder, patch("$APPLICATIONS/$older"), form(firstName = "Mallory")),
                    )
                        .andExpect(status().isNotFound)
                }

                Then("the row is left without a name") {
                    repository.findById(older).orElseThrow().firstName.shouldBeNull()
                }
            }
        }

        Given("an id that names nothing") {

            When("it is a well-formed uuid") {
                Then("the read is not found") {
                    mvc.perform(get("$APPLICATIONS/${UUID.randomUUID()}").header(USER_HEADER, user))
                        .andExpect(status().isNotFound)
                        .andExpect(jsonPath("$.type").value("/problems/not-found"))
                }
            }

            When("it isn't a uuid at all") {
                Then("it is not found, because a malformed id can't identify an application") {
                    mvc.perform(get("$APPLICATIONS/not-a-uuid").header(USER_HEADER, user))
                        .andExpect(status().isNotFound)
                }
            }
        }

        // NotEditable has no test here on purpose: nothing can leave DRAFT until FR4 adds submit, so the API
        // cannot be driven into that state. Its mapping is covered by the other CONFLICTING_STATE case above.
    }

    private fun json(request: MockHttpServletRequestBuilder, body: String) = asUser(user, request, body)
}

private const val APPLICATIONS = "/v1/applications"
private const val USER_HEADER = "X-User-Id"

private fun asUser(user: String, request: MockHttpServletRequestBuilder, body: String) =
    request.header(USER_HEADER, user).contentType(MediaType.APPLICATION_JSON).content(body)

class DockerAvailable : Condition {
    override fun evaluate(kclass: KClass<out Spec>) = dockerIsAvailable()
}

private fun dockerIsAvailable() =
    runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

private fun MvcResult.body(): String = response.contentAsString

private fun MvcResult.id(): UUID = UUID.fromString(Regex(""""id":"([^"]+)"""").find(body())!!.groupValues[1])

/**
 * Runs [task] on [threads] threads released together. The requests have to be in flight at the same time for
 * the unique index and the optimistic lock to be exercised at all.
 */
private fun <T> runConcurrently(threads: Int, task: (Int) -> T): List<T> =
    Executors.newFixedThreadPool(threads).use { pool ->
        val start = CountDownLatch(1)
        val futures = (0 until threads).map { i ->
            pool.submit(Callable { start.await(); task(i) })
        }
        start.countDown()
        futures.map { it.get(30, TimeUnit.SECONDS) }
    }

/**
 * Spring's OutputCaptureExtension is a JUnit parameter resolver, so the log is captured through logback
 * instead — which also catches anything written by an appender other than the console.
 */
private fun captureLogs(block: () -> Unit): String {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    try {
        block()
    } finally {
        root.detachAppender(appender)
        appender.stop()
    }
    return appender.list.joinToString("\n") { "${it.formattedMessage} ${it.throwableProxy?.message.orEmpty()}" }
}
