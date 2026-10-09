package org.openmrs.module.synchronizationmr.api.impl;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.openmrs.api.APIException;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.synchronizationmr.api.SyncAuditService;
import org.openmrs.module.synchronizationmr.sync.SyncAuditRecord;

/** Una fila por intento; solo permite cerrar una fila que todavía está en curso. */
public class SyncAuditServiceImpl extends BaseOpenmrsService implements SyncAuditService {

	private SessionFactory sessionFactory;

	public void setSessionFactory(SessionFactory value) {
		sessionFactory = value;
	}

	public String begin(SyncAuditRecord r) {
        if (r == null) { throw new IllegalArgumentException("Faltan metadatos de auditoría"); }
        String id = UUID.randomUUID().toString();
        sessionFactory.getCurrentSession().doWork(connection -> {
            try (PreparedStatement q = connection.prepareStatement("insert into synchronizationmr_audit "
                    + "(attempt_uuid,source_node,destination_node,entity_type,action,event_origin,event_uuid,"
                    + "entity_sequence,started_at,result) values (?,?,?,?,?,?,?,?,?,?)")) {
                q.setString(1,id); q.setString(2,r.source); q.setString(3,r.destination);
                q.setString(4,r.entityType); q.setString(5,r.action); q.setString(6,r.eventOrigin);
                q.setString(7,r.eventUuid);
                if (r.sequence == null) { q.setNull(8,java.sql.Types.BIGINT); } else { q.setLong(8,r.sequence); }
                q.setTimestamp(9,new Timestamp(System.currentTimeMillis())); q.setString(10,"IN_PROGRESS");
                q.executeUpdate();
            }
        });
        return id;
    }

	public void finish(String id, String result, String code) {
        UUID.fromString(id);
        if (!java.util.Arrays.asList("CONFIRMED", "RECEIVED", "SERVED", "SUCCEEDED", "FAILED", "UNCONFIRMED").contains(result)
                || !java.util.Arrays.asList("OK", "COMMUNICATION", "REJECTED", "INVALID", "INTERNAL").contains(code)
                || (java.util.Arrays.asList("FAILED", "UNCONFIRMED").contains(result) == "OK".equals(code))) {
            throw new IllegalArgumentException("Resultado de auditoría inválido");
        }
        sessionFactory.getCurrentSession().doWork(connection -> {
            try (PreparedStatement q = connection.prepareStatement("update synchronizationmr_audit set "
                    + "finished_at=?,result=?,result_code=? where attempt_uuid=? and result='IN_PROGRESS'")) {
                q.setTimestamp(1,new Timestamp(System.currentTimeMillis())); q.setString(2,result);
                q.setString(3,code); q.setString(4,id);
                if (q.executeUpdate()!=1) { throw new APIException("Intento inexistente o ya cerrado"); }
            }
        });
    }
}
