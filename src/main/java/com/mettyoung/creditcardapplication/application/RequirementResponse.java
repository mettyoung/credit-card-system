package com.mettyoung.creditcardapplication.application;

import java.util.List;

/**
 * What the applicant still owes, as a client sees it.
 * <p>
 * Built by the module's mapper rather than by a factory here: turning a requirement into this needs the
 * aggregate and the accepted document kinds, and neither of those leaves the module.
 *
 * @param acceptedDocumentKinds what the applicant may upload to move this forward — present only while
 *                              something is actually wanted, so a client never offers an upload that would
 *                              be refused
 */
public record RequirementResponse(RequirementType type, RequirementStatus status,
                                  List<String> acceptedDocumentKinds) {
}
