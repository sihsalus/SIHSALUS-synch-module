package org.openmrs.module.synchronizationmr.api.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.openmrs.Order;
import org.openmrs.api.APIException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/** Solo escribe en tablas del módulo. Comparte la transacción de la orden clínico. */
@Repository("synchronizationmr.OrderSyncDao")
public class OrderSyncDao {
	
	private static final String PENDING = " from orders c left join synchronizationmr_order_event e on e.order_id = c.order_id"
	        + " where c.voided = false and (e.event_uuid is null or e.payload_json is null or trim(e.payload_json) = '')";
	
	public java.util.List<Integer> lockAndFindPending(int limit) {
        sessionFactory.getCurrentSession().flush();
        localNodeDao.getLocalServerId();
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("select c.order_id" + PENDING + " order by c.order_id")) {
                query.setMaxRows(limit);
                try (ResultSet rows = query.executeQuery()) { while (rows.next()) { ids.add(rows.getInt(1)); } }
            }
            return ids;
        });
    }
	
	public long countPending() {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement query = connection.prepareStatement("select count(*)" + PENDING); ResultSet rows = query.executeQuery()) {
                rows.next(); return rows.getLong(1);
            }
        });
    }
	
	public void requirePayload(int id) {
        sessionFactory.getCurrentSession().doWork(connection -> {
            try (PreparedStatement query = connection.prepareStatement("select payload_json from synchronizationmr_order_event where order_id = ?")) {
                query.setInt(1, id);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null || rows.getString(1).trim().isEmpty()) {
                        throw new APIException("Evento histórico sin JSON; requiere revisión y no se reconstruye automáticamente");
                    }
                }
            }
        });
    }
	
	public java.util.List<String> findOrderOrigins(String afterOrigin, int limit) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            java.util.List<String> origins = new java.util.ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "select distinct origin_server_id from synchronizationmr_order_event where origin_server_id > ? order by origin_server_id")) {
                query.setString(1, afterOrigin); query.setMaxRows(limit);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) { origins.add(rows.getString(1)); }
                }
            }
            return java.util.Collections.unmodifiableList(origins);
        });
    }
	
	public long findHighestOrderSequence(String origin) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "select coalesce(max(entity_sequence), 0) from synchronizationmr_order_event where origin_server_id = ?")) {
                query.setString(1, origin);
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }
	
	public java.util.List<org.openmrs.module.synchronizationmr.sync.OrderSyncEvent> findOrderEventsAfter(
            String origin, long afterSequence, int limit) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            java.util.List<org.openmrs.module.synchronizationmr.sync.OrderSyncEvent> events = new java.util.ArrayList<>();
            // No filtramos por estado global: otro destino podría necesitar un evento ya entregado.
            try (PreparedStatement query = connection.prepareStatement(
                    "select i.entity_sequence, i.order_uuid, i.event_uuid, i.payload_json"
                    + " from synchronizationmr_order_event i"
                    + " where i.origin_server_id = ? and i.entity_sequence > ?"
                    + " order by i.entity_sequence asc")) {
                query.setString(1, origin);
                query.setLong(2, afterSequence);
                query.setMaxRows(limit);
                long previous = afterSequence;
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        long sequence = rows.getLong(1);
                        if (previous == Long.MAX_VALUE || sequence != previous + 1) {
                            throw new org.openmrs.api.APIException("No se puede entregar la página: falta la secuencia " + (previous + 1));
                        }
                        String eventUuid = rows.getString(3);
                        String payload = rows.getString(4);
                        if (eventUuid == null || payload == null || payload.trim().isEmpty()) {
                            throw new org.openmrs.api.APIException("No se puede entregar la página: falta el evento o su JSON en la secuencia " + sequence);
                        }
                        events.add(new org.openmrs.module.synchronizationmr.sync.OrderSyncEvent(
                                origin, sequence, eventUuid, rows.getString(2), payload));
                        previous = sequence;
                    }
                }
            }
            return java.util.Collections.unmodifiableList(events);
        });
    }
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessionFactory;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao localNodeDao;
	
	@javax.annotation.Resource(name = "synchronizationmr.OrderCreationPayloadSerializer")
	private org.openmrs.module.synchronizationmr.sync.OrderCreationPayloadSerializer serializer;
	
	public boolean exists(Integer id) {
        if (id == null) { return false; }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select order_id from orders where order_id = ?")) {
                statement.setInt(1, id);
                try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
            }
        });
    }
	
	public void capture(Order order) {
        sessionFactory.getCurrentSession().flush();
        sessionFactory.getCurrentSession().doWork(connection -> {
            String origin = localNodeDao.getLocalServerId();
            long previous;
            try (PreparedStatement statement = connection.prepareStatement(
                    "select order_sequence from synchronizationmr_local_node where singleton_id = 1 for update");
                    ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) { throw new APIException("Falta la fila del nodo local"); }
                previous = rows.getLong(1);
            }
            // Bajo el mismo bloqueo: una llamada repetida no consume otra secuencia.
            try (PreparedStatement statement = connection.prepareStatement(
                    "select event_uuid from synchronizationmr_order_event where order_id = ? for update")) {
                statement.setInt(1, order.getOrderId());
                try (ResultSet rows = statement.executeQuery()) { if (rows.next()) { return; } }
            }
            long sequence = Math.addExact(previous, 1L);
            String eventUuid = UUID.randomUUID().toString();
            Timestamp created = new Timestamp(System.currentTimeMillis());
            String payload = serializer.serialize(order, origin, sequence, eventUuid, created);
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into synchronizationmr_order_event (event_uuid, order_id, order_uuid, patient_id, patient_uuid, origin_server_id, entity_sequence, operation, state, date_created, payload_json) values (?, ?, ?, ?, ?, ?, ?, 'CREATE', 'PENDING', ?, ?)")) {
                statement.setString(1, eventUuid);
                statement.setInt(2, order.getOrderId());
                statement.setString(3, order.getUuid());
                statement.setInt(4, order.getPatient().getPatientId());
                statement.setString(5, order.getPatient().getUuid());
                statement.setString(6, origin);
                statement.setLong(7, sequence);
                statement.setTimestamp(8, created);
                statement.setString(9, payload);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "update synchronizationmr_local_node set order_sequence = ? where singleton_id = 1")) {
                statement.setLong(1, sequence);
                statement.executeUpdate();
            }
        });
    }
}
