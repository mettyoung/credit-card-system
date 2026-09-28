package com.mettyoung.creditcardapplication.architecture

import com.mettyoung.creditcardapplication.CreditCardApplication
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldNotBe
import org.springframework.modulith.core.ApplicationModule
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

/**
 * The module boundaries, enforced rather than described.
 *
 * The MVP spec said boundaries would be "enforced with ArchUnit"; this is that promise kept, and it found real
 * coupling when it was first switched on — three dependency cycles and a dozen reaches into other modules'
 * repositories. A package-private class is invisible outside its package; a whole module's internals are not,
 * and nothing but a test like this notices.
 */
class ModuleStructureTest : DescribeSpec({

    val modules = ApplicationModules.of(CreditCardApplication::class.java)

    describe("the module structure") {

        it("has exactly the modules we think it has") {
            val names = mutableListOf<String>()
            modules.forEach { module: ApplicationModule -> names.add(module.displayName) }

            names shouldContainExactlyInAnyOrder
                listOf("Applications", "Audit log", "Documents", "Shared", "Vendor checks")
        }

        it("respects its own declared boundaries") {
            // Fails on a cycle, and on any use of a type another module has not exposed. Every allowed
            // dependency is written down in that module's package-info.
            modules.verify()
        }

        it("documents itself") {
            // Writes the module canvas and per-module diagrams under build/spring-modulith-docs, so the
            // picture is generated from the verified structure instead of drawn by hand next to it.
            Documenter(modules)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases()

            modules.getModuleByName("application").orElse(null) shouldNotBe null
        }
    }
})
