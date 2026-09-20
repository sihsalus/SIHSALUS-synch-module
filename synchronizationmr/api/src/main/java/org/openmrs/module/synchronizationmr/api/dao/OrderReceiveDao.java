package org.openmrs.module.synchronizationmr.api.dao;

import java.sql.*;
import org.hibernate.SessionFactory;
import org.openmrs.Order;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Order, observations, original event and receipt commit together. */
@Repository("synchronizationmr.OrderReceiveDao")
public class OrderReceiveDao {
	
	@javax.annotation.Resource(name = "sessionFactory")
	private SessionFactory sessionFactory;
	
	@javax.annotation.Resource(name = "synchronizationmr.LocalNodeDao")
	private LocalNodeDao localNodeDao;
	
	@javax.annotation.Resource(name = "synchronizationmr.OrderLinkDao")
	private OrderLinkDao orderLinks;
	
	public long confirmed(String origin) {
        return sessionFactory.getCurrentSession().doReturningWork(connection -> confirmed(connection, origin));
    }
	
	private long confirmed(Connection connection, String origin) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "select confirmed_sequence from synchronizationmr_order_receipt where origin_server_id = ?")) {
            query.setString(1, origin);
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        }
    }
	
	public long receive(OrderIncomingEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) { throw new APIException("La recepción requiere transacción de escritura"); }
        if (localNodeDao.getLocalServerId().equals(event.origin)) { throw new APIException("No se importa el propio origen"); }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            long confirmed = confirmed(connection, event.origin);
            if (event.sequence <= confirmed) {
                try (PreparedStatement query = connection.prepareStatement(
                        "select order_uuid, event_uuid, payload_json from synchronizationmr_order_event where origin_server_id = ? and entity_sequence = ?")) {
                    query.setString(1, event.origin); query.setLong(2, event.sequence);
                    try (ResultSet rows = query.executeQuery()) {
                        if (rows.next() && event.orderUuid.equals(rows.getString(1)) && event.eventUuid.equals(rows.getString(2))
                                && event.sameContent(rows.getString(3))) { return confirmed; }
                    }
                }
                throw new APIException("El evento no coincide con la secuencia ya confirmada");
            }
            if (confirmed == Long.MAX_VALUE || event.sequence != confirmed + 1) { throw new APIException("Falta una secuencia anterior de órdenes"); }
            if (Context.getOrderService().getOrderByUuid(event.orderUuid) != null) { throw new APIException("La orden ya existe sin esta recepción confirmada"); }
            Order incoming = event.toOrder();
            event.preparePreviousSnapshot(incoming, connection, sessionFactory.getCurrentSession());
            Order saved = IncomingOrderSave.save(incoming, () -> Context.getOrderService().saveRetrospectiveOrder(incoming, null));
            sessionFactory.getCurrentSession().flush();
            event.restoreSnapshotDates(saved, connection);
            sessionFactory.getCurrentSession().refresh(saved);
            if (saved.getPreviousOrder()!=null) sessionFactory.getCurrentSession().refresh(saved.getPreviousOrder());
            orderLinks.resolve(saved);
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into synchronizationmr_order_event (event_uuid, order_id, order_uuid, patient_id, patient_uuid, origin_server_id, entity_sequence, operation, state, date_created, payload_json) values (?, ?, ?, ?, ?, ?, ?, 'CREATE', 'PENDING', ?, ?)")) {
                insert.setString(1, event.eventUuid); insert.setInt(2, saved.getOrderId()); insert.setString(3, event.orderUuid);
                insert.setInt(4, saved.getPatient().getPatientId()); insert.setString(5, event.patientUuid);
                insert.setString(6, event.origin); insert.setLong(7, event.sequence); insert.setTimestamp(8, new Timestamp(event.occurredAt.getTime()));
                insert.setString(9, event.json); insert.executeUpdate();
            }
            if (confirmed == 0) {
                try (PreparedStatement insert = connection.prepareStatement("insert into synchronizationmr_order_receipt (origin_server_id, confirmed_sequence) values (?, ?)")) {
                    insert.setString(1, event.origin); insert.setLong(2, event.sequence); insert.executeUpdate();
                }
            } else {
                try (PreparedStatement update = connection.prepareStatement("update synchronizationmr_order_receipt set confirmed_sequence = ? where origin_server_id = ?")) {
                    update.setLong(1, event.sequence); update.setString(2, event.origin); update.executeUpdate();
                }
            }
            return event.sequence;
        });
    }
	
	public long countPendingOrderLinks() {
		return orderLinks.countPending();
	}
}
