package com.mettyoung.creditcardapplication.web

import com.mettyoung.creditcardapplication.support.DockerAvailable
import com.mettyoung.creditcardapplication.support.dockerIsAvailable
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * FR10: the app serves its own web UI, so the UI and the API share an origin and the API needs no CORS. What the
 * screens do is walked through by hand (fr10-web-ui.md §8); this pins that the files are there and typed right.
 */
@SpringBootTest(properties = ["app.workers.enabled=false"])
@AutoConfigureMockMvc
@EnabledIf(DockerAvailable::class)
class WebUiTest : DescribeSpec() {

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

    @Autowired private lateinit var mvc: MockMvc

    init {
        extension(SpringExtension())

        describe("the web UI") {

            it("is the welcome page at /") {
                mvc.perform(get("/")).andExpect(status().isOk).andExpect(forwardedUrl("index.html"))
            }

            withData(
                nameFn = { "serves ${it.first} as ${it.second}" },
                "/index.html" to "text/html",
                "/app.js" to "text/javascript",
                "/api.js" to "text/javascript",
                "/styles.css" to "text/css",
            ) { (path, type) ->
                val response = mvc.perform(get(path)).andExpect(status().isOk).andReturn().response

                response.contentType!! shouldStartWith type
            }

            it("loads its script as a module, so app.js can import api.js") {
                mvc.perform(get("/index.html")).andReturn().response.contentAsString shouldContain
                    """<script type="module" src="app.js">"""
            }
        }
    }
}
