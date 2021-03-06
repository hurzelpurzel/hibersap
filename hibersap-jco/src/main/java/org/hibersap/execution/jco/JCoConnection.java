/*
 * Copyright (c) 2008-2019 akquinet tech@spree GmbH
 *
 * This file is part of Hibersap.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hibersap.execution.jco;

import com.sap.conn.jco.JCoCustomDestination;
import com.sap.conn.jco.JCoCustomDestination.UserData;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoRepository;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibersap.HibersapException;
import org.hibersap.execution.Connection;
import org.hibersap.mapping.model.BapiMapping;
import org.hibersap.session.Credentials;
import org.hibersap.session.SessionImplementor;
import org.hibersap.session.Transaction;
import static java.util.Objects.nonNull;

/*
 * @author Carsten Erker
 */
@SuppressWarnings("PackageAccessibility")
public class JCoConnection implements Connection {

    private static final Log LOG = LogFactory.getLog(JCoConnection.class);

    private final JCoContextAdapter jcoContext = new JCoContextAdapterImpl();
    private final JCoMapper jcoMapper;
    private final String destinationName;
    private JCoDestination destination = null;
    private JCoTransaction transaction = null;
    private Credentials credentials;

    public JCoConnection(final String destinationName) {
        this.jcoMapper = new JCoMapper();
        this.destinationName = destinationName;
    }

    /**
     * {@inheritDoc}
     */
    public Transaction beginTransaction(final SessionImplementor session) {
        if (transaction != null) {
            return transaction;
        }
        transaction = new JCoTransaction(session);
        transaction.begin();

        return transaction;
    }

    public void close() {
        if (destination != null) {
            endStatefulConnection();
        }
    }

    public void execute(final BapiMapping bapiMapping, final Map<String, Object> functionMap) {
        if (destination == null) {
            startStatefulConnection();
        }

        JCoFunction function;

        try {
            function = getRepository().getFunction(bapiMapping.getBapiName());
        } catch (JCoException e) {
            throw new HibersapException("The function " + bapiMapping.getBapiName() + " is not available in the SAP system", e);
        }
        if (function == null) {
            throw new HibersapException("The function module '" + bapiMapping.getBapiName() + "' does not exist in SAP");
        }

        jcoMapper.putFunctionMapValuesToFunction(function, functionMap);

        try {
            function.execute(destination);
        } catch (JCoException e) {
            throw new HibersapException("Error executing function module " + bapiMapping.getBapiName(), e);
        }

        jcoMapper.putFunctionValuesToFunctionMap(function, functionMap);
    }

    /**
     * {@inheritDoc}
     */
    public Transaction getTransaction() {
        return transaction;
    }

    /**
     * {@inheritDoc}
     */
    public void setCredentials(final Credentials credentials) {
        this.credentials = credentials;
    }

    private void copyCredentialsToUserData(final Credentials cred, final UserData data) {
        if (nonNull(cred.getAliasUser())) {
            data.setAliasUser(cred.getAliasUser());
        }
        if (nonNull(cred.getClient())) {
            data.setClient(cred.getClient());
        }
        if (nonNull(cred.getLanguage())) {
            data.setLanguage(cred.getLanguage());
        }
        if (nonNull(cred.getPassword())) {
            data.setPassword(cred.getPassword());
        }
        if (nonNull(cred.getSsoTicket())) {
            data.setSSOTicket(cred.getSsoTicket());
        }
        if (nonNull(cred.getUser())) {
            data.setUser(cred.getUser());
        }
        if (nonNull(cred.getX509Certificate())) {
            data.setX509Certificate(cred.getX509Certificate());
        }
    }

    private void endStatefulConnection() {
        try {
            jcoContext.end(destination);
        } catch (JCoException e) {
            LOG.warn("JCo connection could not be ended, ignoring it for now...", e);
        } finally {
            destination = null;
        }
    }

    private JCoCustomDestination getCustomDestination(final JCoDestination dest, final Credentials cred) {
        JCoCustomDestination custDest = dest.createCustomDestination();
        UserData data = custDest.getUserLogonData();
        copyCredentialsToUserData(cred, data);
        return custDest;
    }

    private JCoRepository getRepository() {
        try {
            return destination.getRepository();
        } catch (JCoException e) {
            throw new HibersapException("Can not get repository from destination " + destination.getDestinationName(),
                    e);
        }
    }

    private void startStatefulConnection() {
        destination = JCoEnvironment.getDestination(destinationName);

        if (jcoContext.isStateful(destination)) {
            throw new HibersapException("A stateful JCo session was already started for the given destination "
                    + "in the current thread.");
        }

        if (credentials != null) {
            destination = getCustomDestination(destination, credentials);
        }

        jcoContext.begin(destination);
    }
}