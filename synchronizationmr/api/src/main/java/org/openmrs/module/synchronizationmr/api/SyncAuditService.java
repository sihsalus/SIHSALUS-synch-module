package org.openmrs.module.synchronizationmr.api;

import org.openmrs.api.OpenmrsService;
import org.openmrs.annotation.Authorized;
import org.openmrs.module.synchronizationmr.sync.SyncAuditRecord;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Transacciones independientes: el rechazo de datos clínicos no borra la auditoría. */
public interface SyncAuditService extends OpenmrsService {

	@Authorized("Record Synchronization Audit")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	String begin(SyncAuditRecord record);

	@Authorized("Record Synchronization Audit")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	void finish(String attemptUuid, String result, String code);
}
