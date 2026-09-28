package com.mettyoung.creditcardapplication.document;

/**
 * What the client claims the object will be: the request body of {@code POST /documents} and the module's
 * input, in one type. A command rather than a request, because it is what a caller asks the module to do
 * whether or not that caller arrived over HTTP.
 * <p>
 * The fields are the value objects themselves, not the strings they parse from. Each one validates in its own
 * constructor, so binding the body <em>is</em> the validation — there is no window in which a command exists
 * holding a content type nobody has checked, and no parsing step in the service to forget. A non-HTTP caller
 * gets the same guarantee, because the only way to build one is through the same constructors.
 * <p>
 * No Bean Validation annotations, for that reason. {@code sizeBytes} is the exception: it has no value object
 * of its own, so its rule lives in
 * {@link com.mettyoung.creditcardapplication.document.internal.Document}'s factory, which rejects a size
 * outside the allowed bounds by name.
 */
public record RequestUploadCommand(DocumentKind kind, ContentType contentType, long sizeBytes, Sha256 sha256) {
}
