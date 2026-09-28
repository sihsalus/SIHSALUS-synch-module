package org.openmrs.module.synchronizationmr.sync;

import org.openmrs.api.APIException;

/** Retry this origin after other encounter origins have had a chance to arrive. */
public class EncounterDependencyException extends APIException {
	
	public EncounterDependencyException(String message) {
		super(message);
	}
}
