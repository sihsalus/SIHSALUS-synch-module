package org.openmrs.module.synchronizationmr.advice;

import org.aopalliance.intercept.*;
import org.openmrs.Visit;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.api.EncounterSyncService;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Captura el cierre, la anulación y los cambios de datos de visita en la transacción del guardado nativo. */
public class VisitChangeAdvice implements MethodInterceptor {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
    public Object invoke(MethodInvocation call) throws Throwable {
        String method=call.getMethod().getName();
        if (ACTIVE.get()!=null || (call.getArguments().length>0 && call.getArguments()[0] instanceof Visit && org.openmrs.module.synchronizationmr.sync.VisitAnnulmentScope.contains((Visit)call.getArguments()[0])) || !(method.equals("saveVisit") || method.equals("endVisit") || method.equals("voidVisit"))
            || call.getArguments().length<1 || !(call.getArguments()[0] instanceof Visit)) return call.proceed();
        if (TransactionSynchronizationManager.isActualTransactionActive()) return capture(call);
        return new TransactionTemplate(Context.getRegisteredComponent("transactionManager",PlatformTransactionManager.class)).execute(status -> {
            try { return capture(call); } catch(RuntimeException|Error ex) { throw ex; }
            catch(Throwable ex) { throw new APIException("No se pudo capturar el cambio de visita",ex); }
        });
    }
    private Object capture(MethodInvocation call) throws Throwable {
        ACTIVE.set(true);
        try {
            EncounterSyncService service=Context.getService(EncounterSyncService.class);
            service.lockEncounterChanges();
            Object result;
            if ("voidVisit".equals(call.getMethod().getName())) {
                try (org.openmrs.module.synchronizationmr.sync.VisitAnnulmentScope scope = org.openmrs.module.synchronizationmr.sync.VisitAnnulmentScope.enter((Visit)call.getArguments()[0])) { result=call.proceed(); }
            } else result=call.proceed();
            service.recordVisitInterval((Visit)call.getArguments()[0]);
            return result;
        } finally { ACTIVE.remove(); }
    }
}
