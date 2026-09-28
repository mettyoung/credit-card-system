/**
 * What every module may use: the error category, time-ordered ids, and the clock.
 * <p>
 * Depends on nothing, and must stay that way — a dependency here would be one every module inherits.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Shared")
package com.mettyoung.creditcardapplication.shared;
