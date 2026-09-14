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
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.openmrs.api.APIException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Conserva una sola identidad local sin depender de ninguna entidad clínica. */
@Repository("synchronizationmr.LocalNodeDao")
public class LocalNodeDao {
	
	@Autowired
	private SessionFactory sessionFactory;
	
	public String getOrCreateNodeUuid() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new APIException("La identidad del nodo requiere una transacción de escritura activa");
        }
        return sessionFactory.getCurrentSession().doReturningWork(connection -> {
            // El bloqueo evita generar identidades diferentes ante dos primeras llamadas simultáneas.
            // Se conserva hasta confirmar o deshacer la transacción del llamador.
            String uuid;
            try (PreparedStatement query = connection.prepareStatement(
                    "select node_uuid from synchronizationmr_local_node where singleton_id = 1 for update");
                    ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Falta la fila del nodo; revise las migraciones del módulo");
                }
                uuid = rows.getString(1);
            }
            if (uuid == null) {
                uuid = UUID.randomUUID().toString();
                try (PreparedStatement update = connection.prepareStatement(
                        "update synchronizationmr_local_node set node_uuid = ? where singleton_id = 1")) {
                    update.setString(1, uuid);
                    update.executeUpdate();
                }
            }
            return uuid;
        });
    }
}
