/**
 * The application itself: the draft an applicant fills in, the declared data it holds, and the endpoints the
 * applicant drives it through.
 * <p>
 * The exposed surface is this package: {@code Applications} to drive it, {@code CreateDraftCommand} and
 * {@code UpdateDraftCommand} as what goes in, {@code ApplicationResponse} as what comes back, and the two
 * enums those name. Everything else —
 * the aggregate, its value objects and converters, the repository, the service, the endpoints and the
 * exceptions — is package-private in {@code internal}, so the compiler refuses to let another module name it.
 * <p>
 * The exceptions are internal on purpose. Refusals cross the boundary as {@code DomainException}s carrying a
 * {@code Category}, and the category is the contract — a caller that switched on the concrete class would be
 * depending on a name this module is free to change.
 * <p>
 * {@code internal} is one package rather than the usual {@code web}/{@code service}/{@code domain}/
 * {@code persistence} split, because package-private does not span sub-packages: layering them would have
 * forced every one of those types back to {@code public}, which is how they started. The layers survive as a
 * rule about what each class may do, enforced in review rather than by the package name.
 * <p>
 * Depends only on {@code shared}. Later increments add collaborators, and each one has to be named here
 * before this module is allowed to reach it - an import that is not on the list fails
 * {@code ModuleStructureTest}.
 * <p>
 * The document endpoints live here rather than in {@code document}, because the resource is application-scoped
 * and the guard on it is the application's. That direction is also the only one that is not a cycle.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Applications",
        allowedDependencies = { "audit", "document", "vendor", "shared", "shared::outbox" })
package com.mettyoung.creditcardapplication.application;
