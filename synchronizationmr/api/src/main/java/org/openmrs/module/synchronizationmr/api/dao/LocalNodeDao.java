/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.synchronizationmr.api.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.hibernate.SessionFactory;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.synchronizationmr.sync.ServerId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fija server.id bajo el mismo bloqueo que protege los contadores. No genera UUID. */
@Repository("synchronizationmr.LocalNodeDao")
public class LocalNodeDao {
	
	@Autowired
	private SessionFactory sessionFactory;
	
	public String getLocalServerId() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new APIException("La identidad del nodo requiere una transacción de escritura activa");
        }
        String configured = Context.getAdministrationService().getGlobalProperty(ServerId.PROPERTY);
        if (!ServerId.isValid(configured)) {
            throw new APIException("Configure la Global Property server.id antes de capturar o sincronizar registros");
        }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            String existing;
            try (PreparedStatement query = connection.prepareStatement(
                    "select server_id from synchronizationmr_local_node where singleton_id = 1 for update");
                    ResultSet rows = query.executeQuery()) {
                if (!rows.next()) { throw new SQLException("Falta la fila del nodo; revise las migraciones del módulo"); }
                existing = rows.getString(1);
            }
            if (existing != null && !existing.equals(configured)) {
                throw new APIException("server.id cambió después de fijar la identidad; restaure la configuración del establecimiento");
            }
            if (existing == null) {
                try (PreparedStatement update = connection.prepareStatement(
                        "update synchronizationmr_local_node set server_id = ? where singleton_id = 1")) {
                    update.setString(1, configured);
                    update.executeUpdate();
                }
            }
            return configured;
        });
    }
}
