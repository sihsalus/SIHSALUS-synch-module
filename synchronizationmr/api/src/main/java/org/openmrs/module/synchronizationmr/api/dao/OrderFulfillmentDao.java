package org.openmrs.module.synchronizationmr.api.dao;

import java.sql.*;
import java.time.Instant;
import org.hibernate.SessionFactory;
import org.openmrs.Order;
import org.openmrs.api.APIException;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.stereotype.Repository;

/** Guarda cambios de cumplimiento, su versión ganadora y el evento en la misma transacción. */
@Repository("synchronizationmr.OrderFulfillmentDao")
public class OrderFulfillmentDao {

	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessions;

	@javax.annotation.Resource(name = "synchronizationmr.OrderConflictDao")
	private OrderConflictDao conflicts;

	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao node;

	/** Bloquea antes del guardado nativo y lee valores persistidos, sin adelantar el flush. */
	public String before(Order order) {
        node.getLocalServerId();
        if (order.getId() == null) return null;
        return sessions.getCurrentSession().doReturningWork(c -> {
            try (PreparedStatement q = c.prepareStatement("select fulfiller_status,fulfiller_comment,accession_number from orders where order_id=?")) {
                q.setInt(1, order.getId());
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next()) return null;
                    Order persisted = new Order();
                    String status = r.getString(1);
                    persisted.setFulfillerStatus(status == null ? null : Order.FulfillerStatus.valueOf(status));
                    persisted.setFulfillerComment(r.getString(2));
                    persisted.setAccessionNumber(r.getString(3));
                    return OrderFulfillment.snapshot(persisted).toString();
                }
            }
        });
    }

	public void capture(Order order, String before) {
        if (before == null || Boolean.TRUE.equals(order.getVoided())
                || before.equals(OrderFulfillment.snapshot(order).toString())) return;
        String origin = node.getLocalServerId();
        sessions.getCurrentSession().flush();
        sessions.getCurrentSession().doWork(c -> {
            OrderIncomingEvent known = version(c, order.getId());
            // Una orden anterior sin publicar se prepara después con su estado actual.
            if (known == null) return;
            long seq;
            try (PreparedStatement q = c.prepareStatement("select order_sequence from synchronizationmr_local_node where singleton_id=1"); ResultSet r = q.executeQuery()) {
                if (!r.next()) throw new APIException("Falta la identidad del nodo");
                seq = Math.addExact(r.getLong(1), 1);
            }
            long time = Math.max(System.currentTimeMillis(), Math.addExact(known.occurredAt.getTime(), 1));
            OrderIncomingEvent event = new OrderIncomingEvent(OrderFulfillment.create(order, origin, seq, Instant.ofEpochMilli(time)));
            insert(c, order, event);
            saveVersion(c, order, event);
            try (PreparedStatement q = c.prepareStatement("update synchronizationmr_local_node set order_sequence=? where singleton_id=1")) {
                q.setLong(1, seq); q.executeUpdate();
            }
        });
    }

	public void receive(Connection c, OrderIncomingEvent event) throws SQLException {
        if (!Context.hasPrivilege("Edit Orders")) throw new APIAuthenticationException("El cumplimiento requiere Edit Orders");
        Order order = Context.getOrderService().getOrderByUuid(event.orderUuid);
        if (order == null) throw new APIException("Falta la orden cuyo cumplimiento se actualiza");
        if (!event.patientUuid.equals(order.getPatient().getUuid())
                || !event.root.path("payload").path("encounterUuid").asText().equals(order.getEncounter().getUuid())) {
            throw new APIException("El paciente o encuentro no corresponde a la orden");
        }
        OrderIncomingEvent known = version(c, order.getId());
        if (known == null) throw new APIException("Falta el evento de creación de la orden");
        if (OrderFulfillment.compare(event, known) > 0) {
            // No reactivar ni modificar datos clínicos anulados por la cascada de un encuentro.
            if ((!Boolean.TRUE.equals(order.getVoided()) || conflicts.isSuperseded(order)) && !Boolean.TRUE.equals(order.getEncounter().getVoided())) {
                OrderFulfillment.apply(event.root.path("payload"), order);
                IncomingOrderSave.save(order, () -> Context.getOrderService().updateOrderFulfillerStatus(
                    order, order.getFulfillerStatus(), order.getFulfillerComment(), order.getAccessionNumber()));
                sessions.getCurrentSession().flush();
            }
            saveVersion(c, order, event);
        }
        // También se conserva el evento perdedor para retransmitirlo y confirmar su secuencia.
        insert(c, order, event);
    }

	private OrderIncomingEvent version(Connection c, int id) throws SQLException {
        for (String table : new String[] {"synchronizationmr_order_state", "synchronizationmr_order_event"}) {
            String field = table.endsWith("state") ? "version_json" : "payload_json";
            try (PreparedStatement q = c.prepareStatement("select " + field + " from " + table + " where order_id=?")) {
                q.setInt(1, id);
                try (ResultSet r = q.executeQuery()) {
                    if (r.next()) {
                        String json = r.getString(1);
                        if (json == null || json.trim().isEmpty()) throw new APIException("La orden publicada carece de JSON");
                        return new OrderIncomingEvent(json);
                    }
                }
            }
        }
        return null;
    }

	private void saveVersion(Connection c, Order order, OrderIncomingEvent event) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("update synchronizationmr_order_state set version_json=? where order_id=?")) {
            q.setString(1, event.json); q.setInt(2, order.getId());
            if (q.executeUpdate() > 0) return;
        }
        try (PreparedStatement q = c.prepareStatement("insert into synchronizationmr_order_state (order_id,version_json) values (?,?)")) {
            q.setInt(1, order.getId()); q.setString(2, event.json); q.executeUpdate();
        }
    }

	private void insert(Connection c, Order order, OrderIncomingEvent event) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("insert into synchronizationmr_order_update (event_uuid,order_id,order_uuid,origin_server_id,entity_sequence,date_created,payload_json) values (?,?,?,?,?,?,?)")) {
            q.setString(1, event.eventUuid); q.setInt(2, order.getId()); q.setString(3, order.getUuid());
            q.setString(4, event.origin); q.setLong(5, event.sequence);
            q.setTimestamp(6, new Timestamp(event.occurredAt.getTime())); q.setString(7, event.json); q.executeUpdate();
        }
    }
}
