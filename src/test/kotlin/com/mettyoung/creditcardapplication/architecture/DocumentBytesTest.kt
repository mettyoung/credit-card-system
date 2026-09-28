package com.mettyoung.creditcardapplication.architecture

import com.mettyoung.creditcardapplication.document.Documents
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.properties.HasName
import com.tngtech.archunit.core.domain.properties.HasOwner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import io.kotest.core.spec.style.DescribeSpec

/**
 * Document bytes must not leave over HTTP.
 *
 * The write path already settles this in the other direction: the client PUTs straight to the object store
 * through a pre-signed URL and the API never touches the body. Reading has to hold the same line — a download
 * belongs behind a pre-signed GET, not proxied through a controller that would hold a connection open and pull
 * up to 10 MB into heap to do it.
 *
 * Modulith enforces which *modules* may reach `Documents`; it cannot say which *classes* may call one method
 * on it. That is what these rules are for.
 *
 * The subject is what a class *is* — anything Spring will ask to produce an HTTP response — rather than the
 * package it sits in, so moving the web layer cannot quietly retire the rule.
 */
class DocumentBytesTest : DescribeSpec({

    val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.mettyoung.creditcardapplication")

    // Matched by owner as well as by name, so typing the variable as the implementation rather than the
    // interface does not slip past.
    val contentFor = JavaCall.Predicates.target(HasName.Predicates.name("contentFor"))
        .and(JavaCall.Predicates.target(
            HasOwner.Predicates.With.owner(JavaClass.Predicates.assignableTo(Documents::class.java))))

    describe("anything that answers an HTTP request") {

        it("cannot ask for a document's bytes") {
            noClasses()
                .that().areAnnotatedWith(RestController::class.java)
                .or().areAnnotatedWith(RestControllerAdvice::class.java)
                .should().callMethodWhere(contentFor)
                .because("bytes go to the client through a pre-signed URL, never through the API")
                .check(classes)
        }

        it("cannot even hold them, however they were obtained") {
            // The call ban alone would still let a controller take Content back from something else and write
            // it to the response. No such class may name the type at all.
            noClasses()
                .that().areAnnotatedWith(RestController::class.java)
                .or().areAnnotatedWith(RestControllerAdvice::class.java)
                .should().dependOnClassesThat().areAssignableTo(Documents.Content::class.java)
                .because("a response body is built from a verdict, never from file content")
                .check(classes)
        }
    }
})
