/*
 * Copyright 2015 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.utils;

import static org.slf4j.LoggerFactory.getLogger;
import static org.fcrepo.kernel.modeshape.utils.NamespaceTools.getNamespaces;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.jcr.NamespaceException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeIterator;
import javax.jcr.query.*;

import com.opencsv.CSVWriter;

import org.fcrepo.http.commons.session.SessionFactory;
import org.modeshape.jcr.api.nodetype.NodeTypeManager;
import org.slf4j.Logger;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

/**
 * Utility to manipulate namespace prefixes
 *
 * @author acoburn
 * @since May 3, 2016
 **/
public class NamespaceUtil {

    private final Logger LOGGER = getLogger(NamespaceUtil.class);

    @Inject
    private SessionFactory sessionFactory;

    private Session session;

    private Workspace workspace;

    private NamespaceRegistry namespaceRegistry;

    private QueryManager queryManager;

    /**
     * Start and run the namespace utility
     **/
    public static void main(final String[] args) {
        ConfigurableApplicationContext ctx = null;
        try {
            final NamespaceUtil nsUtil = new NamespaceUtil();
            ctx = new ClassPathXmlApplicationContext("classpath:/spring/master.xml");
            ctx.getBeanFactory().autowireBeanProperties(nsUtil, AutowireCapableBeanFactory.AUTOWIRE_BY_TYPE, false);

            getPropertyOrExit("fcrepo.home", "/path/to/fcrepo/home");
            getPropertyOrExit("fcrepo.modeshape.configuration", "/repo.json");
            
            nsUtil.run();
        } catch (RepositoryException ex) {
            ex.printStackTrace();
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            if (null != ctx) {
                ctx.close();
            }
        }
    }

    public static String getPropertyOrExit(String propName, String sampleValue) {
        if (System.getProperty(propName) == null) {
            System.err.println("java -D" + propName + "=" + sampleValue + " ...");
            System.exit(1);
        }
        return System.getProperty(propName);
    }

    /**
     * Run the namespace change utility
     **/
    public void run() throws RepositoryException, IOException {
        LOGGER.info("Starting namespace utility");

        session = sessionFactory.getInternalSession();
        workspace = session.getWorkspace();
        namespaceRegistry = workspace.getNamespaceRegistry();
        queryManager = workspace.getQueryManager();

        String command = getPropertyOrExit("command", "list|check");

        if ("list".equalsIgnoreCase(command)) {
            String filepath = getPropertyOrExit("filepath", "/path/to/output/file");
            list(filepath);
        } else if ("check".equalsIgnoreCase(command)) {
            String filepath = getPropertyOrExit("filepath", "/path/to/input/file");
            // checkNamespacesInUse(filepath);
        } else {
            System.err.println("Unknown command: " + command);
            System.exit(1);
        }
        
        LOGGER.info("Stopping namespace utility");
    }

    // get the set of "nsXXX" prefixes
    private Set<String> getNSXXXPrefixes() {
        return getNamespaces(session).keySet().stream().filter((k) -> k.startsWith("ns")).collect(Collectors.toSet());
    }

    private void list(String filepath) throws RepositoryException, IOException {
        try {
            CSVWriter writer = new CSVWriter(new FileWriter(filepath));
            // Write data to the CSV file
            String[] data = {"prefix", "resource"};
            writer.writeNext(data);
        
            for (final String prefix: getNSXXXPrefixes()) {
                data[0] = prefix;
                System.out.println(prefix);
                final Query query = queryManager.createQuery(
                        "SELECT * FROM [" + prefix + ":None]",
                        "JCR-SQL2"
                );
                try {
                    final QueryResult result = query.execute();
                    final RowIterator rowIterator = result.getRows();
                    while (rowIterator.hasNext()) {
                        final Row row = rowIterator.nextRow();
                        final String path = row.getPath();
                        System.out.println("  " + path);
                        data[1] = path;
                        writer.writeNext(data);
                    }
                } catch (InvalidQueryException ignored) {
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void checkNamespacesInUse(String inputFile) {
        try {


            // Read namespaces from the input file
            Set<String> namespaces = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    namespaces.add(line);
                }
            }

            NodeTypeManager nodeTypeManager = (NodeTypeManager) workspace.getNodeTypeManager();
            NodeTypeIterator nodeTypes = nodeTypeManager.getAllNodeTypes();

            while (nodeTypes.hasNext()) {
                NodeType nodeType = nodeTypes.nextNodeType();
                NodeType[] declaredPrefixes = nodeType.getDeclaredSupertypes();
                for (String namespace : namespaces) {
                    if (isNamespaceUsed(namespace, declaredPrefixes)) {
                        System.out.println("Namespace " + namespace + " is used by node type " + nodeType.getName());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean isNamespaceUsed(String namespace, NodeType[] prefixes) {
        for (NodeType prefix : prefixes) {
            if (prefix.toString().startsWith(namespace + ":")) {
                return true;
            }
        }
        return false;
    }

    
}

