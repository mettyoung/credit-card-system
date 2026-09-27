/**
 * Append-only record of what happened to an application.
 * <p>
 * A leaf: every other module writes to it and it depends on none of them. Its one allowed dependency is
 * {@code shared}, for the time-ordered id, and that list is what keeps it a leaf — an audit log that reached
 * back into the modules writing to it would put itself in a cycle with all of them at once.
 * <p>
 * It is also why {@link com.mettyoung.creditcardapplication.audit.AuditEntry} carries ids, codes and a map
 * rather than another module's types: the signature cannot acquire a dependency it is not allowed to have.
 * <p>
 * The exposed surface is {@code Audits}, {@code AuditEntry}, {@code AuditEventType} and {@code Actor}, all in
 * this package. The entity, the repository and the implementation are package-private in {@code internal},
 * so the compiler refuses to let anything outside name them — not only {@code ModuleStructureTest}.
 * <p>
 * That is why {@code internal} is one package rather than the usual {@code domain}/{@code persistence}/
 * {@code service} split: package-private does not span sub-packages, so layering them would have forced all
 * three back to {@code public}. Three classes are not worth that trade.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Audit log",
        allowedDependencies = { "shared" })
package com.mettyoung.creditcardapplication.audit;
