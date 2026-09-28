/**
 * Uploaded evidence: pre-signed URLs out, verified objects in.
 * <p>
 * The exposed surface is this package: {@code Uploads} to drive an upload, {@code Documents} to read one,
 * {@code RequestUploadCommand} and {@code DocumentVerified} as what goes in and comes back, and the value objects
 * and exceptions those signatures name. Everything else — the aggregate, the repository, the object store and
 * its S3 adapter, the service and the sweep — is package-private in {@code internal}, so the compiler refuses
 * to let another module name it at all.
 * <p>
 * {@code internal} is one package rather than the usual {@code domain}/{@code persistence}/{@code service}
 * split because package-private does not span sub-packages: layering them would have forced every one of
 * those types back to {@code public}, which is how they started.
 * <p>
 * Depends on no other feature module. The endpoints that drive it live in {@code application}, because the
 * resource is application-scoped and the guard on it is the application's — putting them here would make
 * {@code document} depend on {@code application}, which already depends on this.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Documents",
        allowedDependencies = { "audit", "shared" })
package com.mettyoung.creditcardapplication.document;
