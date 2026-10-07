package org.openmrs.module.synchronizationmr.advice;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Obs;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.api.EncounterSyncService;
import org.openmrs.module.synchronizationmr.sync.IncomingEncounterSave;
import org.openmrs.module.synchronizationmr.sync.ObservationCaptureScope;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Captura adiciones y versiones corregidas guardadas directamente mediante ObsService. */
public class ObservationAdditionAdvice implements MethodInterceptor {
	
	@Override public Object invoke(MethodInvocation invocation) throws Throwable {
        if (!("saveObs".equals(invocation.getMethod().getName()) || "voidObs".equals(invocation.getMethod().getName())) || invocation.getArguments().length != 2
                || !(invocation.getArguments()[0] instanceof Obs)) return invocation.proceed();
        Obs obs = (Obs) invocation.getArguments()[0];
        if (obs.getEncounter() == null || IncomingEncounterSave.isReceiving(obs.getEncounter())
                || ObservationCaptureScope.active(obs.getEncounter())) return invocation.proceed();
        if (TransactionSynchronizationManager.isActualTransactionActive()) return capture(invocation);
        PlatformTransactionManager manager = Context.getRegisteredComponent("transactionManager", PlatformTransactionManager.class);
        return new TransactionTemplate(manager).execute(status -> {
            try { return capture(invocation); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new APIException("No se pudo capturar las observaciones", failure); }
        });
    }
	
	private Object capture(MethodInvocation invocation) throws Throwable {
        try (ObservationCaptureScope scope = ObservationCaptureScope.enter(((Obs) invocation.getArguments()[0]).getEncounter())) {
            Context.getService(EncounterSyncService.class).lockEncounterChanges();
            Obs saved = (Obs) invocation.proceed();
            Context.getService(EncounterSyncService.class).recordAddedObservations(saved.getEncounter());
            return saved;
        }
    }
}
