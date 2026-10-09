package org.openmrs.module.synchronizationmr.api.dao;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.hibernate.SessionFactory;
import org.openmrs.Order;
import org.openmrs.api.APIException;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.OrderFulfillment;
import org.openmrs.module.synchronizationmr.sync.OrderIncomingEvent;
import org.springframework.stereotype.Repository;

/** Resuelve revisiones y suspensiones concurrentes de una misma versión, conservando sus ramas. */
@Repository("synchronizationmr.OrderConflictDao")
public class OrderConflictDao {

	public static final String REASON = "Sincronizacion: version de orden desplazada por un cambio posterior";

	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessions;

	public boolean isSuperseded(Order order) {
        if (order == null || !Boolean.TRUE.equals(order.getVoided()) || !REASON.equals(order.getVoidReason())) return false;
        return sessions.getCurrentSession().doReturningWork(c -> {
            try (PreparedStatement q = c.prepareStatement("select order_id from synchronizationmr_order_conflict where order_id=?")) {
                q.setInt(1, order.getId());
                try (ResultSet r = q.executeQuery()) { return r.next(); }
            }
        });
    }

	public boolean allowsPrevious(OrderIncomingEvent event) {
		JsonNode id = event.root.path("payload").get("previousOrderUuid");
		return replaces(event) && id != null && id.isTextual()
		        && isSuperseded(Context.getOrderService().getOrderByUuid(id.asText()));
	}

	private static boolean replaces(OrderIncomingEvent event) {
		String action = event.root.path("payload").path("action").asText();
		return "REVISE".equals(action) || "DISCONTINUE".equals(action);
	}

	private static String parent(OrderIncomingEvent event) {
		return replaces(event) ? event.root.path("payload").path("previousOrderUuid").asText() : null;
	}

	private static final class Entry {

		final Order order;

		final OrderIncomingEvent event;

		final boolean clinicalVoid;

		Entry(Order order, OrderIncomingEvent event, boolean managed) {
			this.order = order;
			this.event = event;
			this.clinicalVoid = (Boolean.TRUE.equals(order.getVoided()) && !managed)
			        || Boolean.TRUE.equals(order.getEncounter().getVoided());
		}
	}

	public final class Resolution {

		private final Map<String, Entry> entries;

		private final String root;

		private Resolution(Map<String, Entry> entries, String root) {
			this.entries = entries;
			this.root = root;
		}

		/** Solo después del guardado nativo y de conservar el evento recibido. */
		public void finish(Connection c, Order saved, OrderIncomingEvent event) throws SQLException {
            entries.put(saved.getUuid(), new Entry(saved, event, false));
            Map<String, Entry> winners = new HashMap<>();
            for (Entry entry : entries.values()) {
                String predecessor = parent(entry.event);
                if (predecessor == null || entry.clinicalVoid) continue;
                Entry old = winners.get(predecessor);
                if (old == null || OrderFulfillment.compare(entry.event, old.event) > 0) winners.put(predecessor, entry);
            }
            Set<String> selected = new HashSet<>();
            String cursor = root;
            while (cursor != null && selected.add(cursor)) {
                Entry winner = winners.get(cursor);
                cursor = winner == null ? null : winner.order.getUuid();
            }
            for (Entry entry : entries.values()) {
                Order order = entry.order;
                // Una anulación clínica o de su encuentro nunca se revierte por esta resolución.
                if (entry.clinicalVoid || Boolean.TRUE.equals(order.getEncounter().getVoided())) continue;
                Entry next = winners.get(order.getUuid());
                Timestamp stop = timestamp(entry.event.root.path("payload").get("dateStopped"));
                if (next != null) stop = timestamp(next.event.root.path("payload").get("previousOrderDateStopped"));
                boolean losing = !selected.contains(order.getUuid());
                Entry cause = entries.get(root);
                String ancestor = order.getUuid();
                Set<String> seen = new HashSet<>();
                while (seen.add(ancestor)) {
                    Entry current = entries.get(ancestor);
                    String predecessor = current == null ? null : parent(current.event);
                    if (predecessor == null) break;
                    Entry winner = winners.get(predecessor);
                    if (winner != null && !winner.order.getUuid().equals(ancestor)) cause = winner;
                    ancestor = predecessor;
                }
                try (PreparedStatement q = c.prepareStatement("update orders set date_stopped=?,voided=?,void_reason=?,date_voided=?,voided_by=? where order_id=?")) {
                    q.setTimestamp(1, stop); q.setBoolean(2, losing); q.setString(3, losing ? REASON : null);
                    q.setTimestamp(4, losing ? new Timestamp(cause.event.occurredAt.getTime()) : null);
                    if (losing) q.setInt(5, Context.getAuthenticatedUser().getId()); else q.setNull(5, Types.INTEGER);
                    q.setInt(6, order.getId()); q.executeUpdate();
                }
                try (PreparedStatement q = c.prepareStatement("delete from synchronizationmr_order_conflict where order_id=?")) {
                    q.setInt(1, order.getId()); q.executeUpdate();
                }
                if (losing) {
                    try (PreparedStatement q = c.prepareStatement("insert into synchronizationmr_order_conflict (order_id,winner_uuid) values (?,?)")) {
                        q.setInt(1, order.getId()); q.setString(2, cause.order.getUuid()); q.executeUpdate();
                    }
                }
                sessions.getCurrentSession().refresh(order);
            }
        }
	}

	/**
	 * Prepara temporalmente la cadena para validación nativa; cualquier fallo revierte toda la
	 * transacción.
	 */
	public Resolution prepare(Connection c, Order incoming, OrderIncomingEvent event) throws SQLException {
        if (!replaces(event) || incoming.getPreviousOrder() == null) return null;
        Map<String, Entry> all = new HashMap<>();
        try (PreparedStatement q = c.prepareStatement("select order_uuid,payload_json from synchronizationmr_order_event where patient_uuid=?")) {
            q.setString(1, event.patientUuid);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    String payload = r.getString(2);
                    if (payload == null || payload.trim().isEmpty()) continue;
                    Order order = Context.getOrderService().getOrderByUuid(r.getString(1));
                    if (order == null) throw new APIException("Falta una orden publicada de la cadena");
                    all.put(order.getUuid(), new Entry(order, new OrderIncomingEvent(payload), isSuperseded(order)));
                }
            }
        }
        String predecessor = incoming.getPreviousOrder().getUuid();
        if (!all.containsKey(predecessor)) return null;
        String root = predecessor;
        Set<String> path = new HashSet<>();
        while (true) {
            if (!path.add(root)) throw new APIException("La cadena de órdenes contiene un ciclo");
            Entry entry = all.get(root);
            if (entry == null) throw new APIException("Falta la creación de una orden anterior");
            if (entry.clinicalVoid || Boolean.TRUE.equals(entry.order.getEncounter().getVoided())) {
                throw new APIException("Una orden anterior fue anulada clínicamente");
            }
            String previous = parent(entry.event);
            if (previous == null) break;
            root = previous;
        }
        Map<String, Entry> group = new LinkedHashMap<>();
        group.put(root, all.get(root));
        boolean added;
        do {
            added = false;
            for (Entry entry : all.values()) {
                if (!group.containsKey(entry.order.getUuid()) && group.containsKey(parent(entry.event))) {
                    group.put(entry.order.getUuid(), entry); added = true;
                }
            }
        } while (added);
        boolean concurrent = isSuperseded(incoming.getPreviousOrder());
        for (Entry entry : group.values()) {
            if (predecessor.equals(parent(entry.event)) && !entry.clinicalVoid) concurrent = true;
        }
        if (!concurrent) return null;
        if (!Context.hasPrivilege("Edit Orders") || !Context.hasPrivilege("Delete Orders")) {
            throw new APIAuthenticationException("La resolución de órdenes requiere Edit Orders y Delete Orders");
        }
        // No ocultar un cambio de fecha independiente que no esté respaldado por un evento.
        Entry knownStop = null;
        for (Entry entry : group.values()) {
            if (predecessor.equals(parent(entry.event)) && !entry.clinicalVoid
                    && (knownStop == null || OrderFulfillment.compare(entry.event, knownStop.event) > 0)) knownStop = entry;
        }
        if (knownStop != null) {
            Timestamp expected = timestamp(knownStop.event.root.path("payload").get("previousOrderDateStopped"));
            if (!Objects.equals(expected, incoming.getPreviousOrder().getDateStopped())) {
                // Date y Timestamp tienen igualdad asimétrica: comparar milisegundos.
                if (expected == null || incoming.getPreviousOrder().getDateStopped() == null
                        || expected.getTime() != incoming.getPreviousOrder().getDateStopped().getTime()) {
                    throw new APIException("La detención anterior no coincide con la cadena publicada");
                }
            }
        }
        sessions.getCurrentSession().flush();
        for (Entry entry : group.values()) {
            if (entry.clinicalVoid) continue;
            boolean inPath = path.contains(entry.order.getUuid());
            try (PreparedStatement q = c.prepareStatement("update orders set voided=?,date_stopped=case when order_id=? then null else date_stopped end where order_id=?")) {
                q.setBoolean(1, !inPath); q.setInt(2, incoming.getPreviousOrder().getId()); q.setInt(3, entry.order.getId()); q.executeUpdate();
            }
            sessions.getCurrentSession().refresh(entry.order);
        }
        return new Resolution(group, root);
    }

	private static Timestamp timestamp(JsonNode node) {
		return node == null || node.isNull() ? null : Timestamp.from(Instant.parse(node.asText()));
	}
}
