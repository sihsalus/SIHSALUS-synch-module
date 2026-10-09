package org.openmrs.module.synchronizationmr.api.dao;

import java.sql.*;
import java.time.Instant;
import java.util.Date;
import org.hibernate.SessionFactory;
import org.openmrs.Encounter;
import org.openmrs.Order;
import org.openmrs.api.APIException;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.IncomingOrderSave;
import org.openmrs.module.synchronizationmr.sync.OrderIncomingEvent;
import org.openmrs.validator.ValidateUtil;
import org.springframework.stereotype.Repository;

/** Recibe órdenes tardías como historial de una anulación publicada, sin reabrir sus padres. */
@Repository("synchronizationmr.OrderAnnulledEncounterDao")
public class OrderAnnulledEncounterDao {

	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessions;

	@javax.annotation.Resource(name = "synchronizationmr.VisitVoidDao")
	private VisitVoidDao visits;

	public boolean isPublished(Encounter encounter) {
        if (encounter == null || encounter.getId() == null || !Boolean.TRUE.equals(encounter.getVoided())) return false;
        if (visits.isPublished(encounter.getVisit())) return true;
        return sessions.getCurrentSession().doReturningWork(c -> {
            try (PreparedStatement q = c.prepareStatement("select event_uuid from synchronizationmr_encounter_annulment where encounter_id=?")) {
                q.setInt(1, encounter.getId());
                try (ResultSet r = q.executeQuery()) { return r.next(); }
            }
        });
    }

	public boolean applies(OrderIncomingEvent event) {
		return isPublished(Context.getEncounterService().getEncounterByUuid(
		    event.root.path("payload").path("encounterUuid").asText()));
	}

	public boolean allowsPrevious(OrderIncomingEvent event) {
		String uuid = event.root.path("payload").path("previousOrderUuid").asText(null);
		Order previous = uuid == null ? null : Context.getOrderService().getOrderByUuid(uuid);
		return previous != null && isPublished(previous.getEncounter());
	}

	public Order save(Connection c, Order incoming, OrderIncomingEvent event) throws SQLException {
        Encounter encounter = incoming.getEncounter();
        if (!isPublished(encounter)) throw new APIException("Falta la anulación publicada del encuentro o visita");
        if (!Context.hasPrivilege("Delete Orders")) throw new APIAuthenticationException("El historial anulado requiere Delete Orders");
        Order previous = incoming.getPreviousOrder();
        if (previous != null && (!incoming.hasSameOrderableAs(previous)
                || !incoming.getOrderType().equals(previous.getOrderType())
                || !incoming.getCareSetting().equals(previous.getCareSetting())
                || !org.hibernate.Hibernate.getClass(incoming).equals(org.hibernate.Hibernate.getClass(previous)))) {
            throw new APIException("La orden histórica no coincide con su predecesora");
        }
        if (previous != null && previous.getAction() == Order.Action.DISCONTINUE) {
            throw new APIException("Una suspensión no puede ser predecesora de una prescripción");
        }
        Date previousStop = previous == null ? null : previous.getDateStopped();
        String reason = encounter.getVoidReason();
        if (reason == null || reason.trim().isEmpty()) reason = "Encuentro anulado";
        incoming.setVoided(true);
        incoming.setVoidReason(reason);
        incoming.setVoidedBy(Context.getAuthenticatedUser());
        incoming.setDateVoided(encounter.getDateVoided());
        incoming.setCreator(Context.getAuthenticatedUser());
        incoming.setDateCreated(new Date());
        // La API de anulación asigna el número nativo y persiste también las subclases.
        // Al no crear una prescripción vigente, no se ejecuta la lógica de detener otras órdenes.
        ValidateUtil.validate(incoming);
        final String finalReason = reason;
        Order saved = IncomingOrderSave.save(incoming, () -> Context.getOrderService().voidOrder(incoming, finalReason));
        sessions.getCurrentSession().flush();
        // voidOrder limpia la detención del predecesor para REVISE/DISCONTINUE: conservar su estado previo.
        if (previous != null) {
            try (PreparedStatement q = c.prepareStatement("update orders set date_stopped=? where order_id=?")) {
                q.setTimestamp(1, previousStop == null ? null : new Timestamp(previousStop.getTime()));
                q.setInt(2, previous.getId()); q.executeUpdate();
            }
            sessions.getCurrentSession().refresh(previous);
        }
        try (PreparedStatement q = c.prepareStatement("update orders set date_stopped=?,auto_expire_date=? where order_id=?")) {
            q.setTimestamp(1, timestamp(event, "dateStopped"));
            q.setTimestamp(2, timestamp(event, "autoExpireDate"));
            q.setInt(3, saved.getId()); q.executeUpdate();
        }
        sessions.getCurrentSession().refresh(saved);
        return saved;
    }

	private Timestamp timestamp(OrderIncomingEvent event, String field) {
		String value = event.root.path("payload").path(field).asText(null);
		return value == null ? null : Timestamp.from(Instant.parse(value));
	}
}
