package com.mettyoung.creditcardapplication.architecture

import com.mettyoung.creditcardapplication.CreditCardApplication
import io.kotest.core.spec.style.DescribeSpec
import org.springframework.modulith.core.ApplicationModules

/**
 * Module boundaries, enforced rather than described.
 *
 * Here before there is a single module, on purpose. A package-private class is invisible outside its package,
 * but a whole module's internals are not, and nothing except a test like this notices when one module reaches
 * into another's repository. Setting it up now means the first module that needs a boundary is born with one,
 * instead of acquiring one after the coupling already exists.
 *
 * It verifies trivially today — there are no modules to check. That is the point of adding it early.
 */
class ModuleStructureTest : DescribeSpec({

    describe("the module structure") {

        it("respects its own declared boundaries") {
            // Fails on a dependency cycle, and on any use of a type another module has not exposed. Every
            // allowed dependency is written down in that module's package-info.
            ApplicationModules.of(CreditCardApplication::class.java).verify()
        }
    }
})
