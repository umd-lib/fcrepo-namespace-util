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

import static org.fcrepo.kernel.modeshape.utils.NamespaceTools.getNamespaces;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.jcr.NamespaceException;
import javax.jcr.NamespaceRegistry;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.nodetype.NodeTypeIterator;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;

import org.fcrepo.http.commons.session.SessionFactory;
import org.modeshape.jcr.api.nodetype.NodeTypeManager;
import org.modeshape.jcr.value.ValueFormatException;
import org.slf4j.Logger;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

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

    private NodeTypeManager nodeTypeManager;

    private Map<String, Set<String>> parentNamespaceUris = new HashMap<String, Set<String>>();;

    private Map<String, Set<String>> childNamespaceUris = new HashMap<String, Set<String>>();;

    private String startTime;

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
            System.exit(2);
        }
        return System.getProperty(propName);
    }

    /**
     * Run the namespace change utility
     **/
    public void run() throws RepositoryException, IOException {
        LOGGER.info("Starting namespace utility");
        startTime = new SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime());

        session = sessionFactory.getInternalSession();
        workspace = session.getWorkspace();
        namespaceRegistry = workspace.getNamespaceRegistry();
        queryManager = workspace.getQueryManager();
        nodeTypeManager = (NodeTypeManager) session.getWorkspace().getNodeTypeManager();

        String command = getPropertyOrExit("command", "list|check");

        if ("list".equalsIgnoreCase(command)) {
            String filepath = getPropertyOrExit("filepath", "/path/to/output/file");
            boolean skipResources = Boolean.parseBoolean(System.getProperty("skip.resources"));
            list(filepath, skipResources);
        } else if ("add-resources".equalsIgnoreCase(command)) {
            String filepath = getPropertyOrExit("filepath", "/path/to/input/file");
            add_resources(filepath);
        } else if ("clean".equalsIgnoreCase(command)) {
            String filepath = getPropertyOrExit("filepath", "/path/to/input/file");
            String mode = getPropertyOrExit("clean.mode", "nodetype|namespace");
            clean(filepath, mode, System.getProperty("skip.until.prefix"));
        } else {
            System.err.println("Unknown command: " + command);
            System.exit(2);
        }
        
        LOGGER.info("Stopping namespace utility");
    }

    // get the set of "nsXXX" prefixes
    private Map<String, List<String>> getNSXXXPrefixesMap() {
        return getNamespaces(session).keySet().stream().filter((k) -> k.startsWith("ns")).collect(Collectors.toMap(k -> k, k -> new ArrayList<>()));
    }

    // get the set of "nsXXX" prefixes
    private Map<String, List<String>> getSpuriousNodeTypes() throws RepositoryException {
        Map<String, List<String>> namespacesWithNodeType = getNSXXXPrefixesMap();
        NodeTypeManager nodeTypeManager = (NodeTypeManager) workspace.getNodeTypeManager();
        NodeTypeIterator nodeTypes = nodeTypeManager.getAllNodeTypes();

        while (nodeTypes.hasNext()) {
            String nodeTypeName = nodeTypes.nextNodeType().getName();
            String nodeTypePrefix = nodeTypeName.split(":")[0];
            if (nodeTypeName.startsWith("ns") && nodeTypeName.endsWith(":None")) {
                if(namespacesWithNodeType.get(nodeTypePrefix) != null) {
                    namespacesWithNodeType.get(nodeTypePrefix).add(nodeTypeName);
                } else {
                    LOGGER.info("No matching namespace for NodeType: " + nodeTypeName);
                    List<String> nodeTypeList = new ArrayList<String>();
                    nodeTypeList.add(nodeTypeName);
                    namespacesWithNodeType.put(nodeTypePrefix, nodeTypeList);
                }
            }
        }
        return namespacesWithNodeType;
    }

    private void list(String filepath, boolean skipResources) throws RepositoryException, IOException {
        try {
            CSVWriter writer = new CSVWriter(new FileWriter(filepath));
            // Write data to the CSV file
            String[] data = {"namespace", "namespaceUri", "nodeType", "resource"};
            writer.writeNext(data);

            Map<String, List<String>> namespacesWithNodeType = getSpuriousNodeTypes();
            data[3] = "";

            for (final String namespacePrefix: namespacesWithNodeType.keySet()) {
                String namespaceUri = namespaceRegistry.getURI(namespacePrefix);

                // Omit ns prefixed namespaces that is not spurious
                if (! namespaceUri.contains("tx:")) {
                    continue;
                }
                data[0] = namespacePrefix;
                data[1] = namespaceUri;
                List<String> nodeTypesList = namespacesWithNodeType.get(namespacePrefix);
                if (nodeTypesList == null || nodeTypesList.isEmpty()) {
                    data[2] = "";
                    writer.writeNext(data);
                } else {
                    for (final String nodeType: nodeTypesList) {
                        
                        
                        data[2] = nodeType;

                        if (skipResources) {
                            writer.writeNext(data);
                            continue;
                        }

                        LOGGER.info("Processing Prefix " + namespacePrefix);
                        final Query query = queryManager.createQuery(
                                "SELECT * FROM [" + nodeType + "]",
                                "JCR-SQL2"
                        );
                        try {
                            final QueryResult result = query.execute();
                            final RowIterator rowIterator = result.getRows();
                            if (! rowIterator.hasNext()) {
                                data[3] = "";
                                writer.writeNext(data);
                            } else {
                                while (rowIterator.hasNext()) {
                                    final Row row = rowIterator.nextRow();
                                    final String path = row.getPath();
                                    LOGGER.info("  " + path);
                                    data[3] = path;
                                    writer.writeNext(data);
                                }
                            }
                        } catch (InvalidQueryException ignored) {
                        }
                    }
                }
            }
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void add_resources(String filepath) {
        String tempFilePath = filepath.replace(".csv", "-" + startTime + "-resources.csv");
        try (
            CSVWriter withResourcesWriter = new CSVWriter(new FileWriter(tempFilePath));
        ) {
            String[] data = {"namespace", "namespaceUri", "nodeType", "resource"};
            
            withResourcesWriter.writeNext(data);

            // Read namespaces from the input file
            try (CSVReader reader = new CSVReader(new FileReader(filepath))) {
                // Skip header row
                reader.skip(1);

                Iterator<String[]> csvRowIterator = reader.iterator();
                while (csvRowIterator.hasNext()) {
                    data = csvRowIterator.next();
                    LOGGER.info("Processing prefix: " + data[0] + ": ");

                    if (data[3] != null && ! "".equals(data[3])) {
                        withResourcesWriter.writeNext(data);
                        LOGGER.info("  Resource exists - writing as-is.");
                        continue;
                    }


                    final Query query = queryManager.createQuery(
                        "SELECT * FROM [" + data[2] + "]",
                        "JCR-SQL2"
                    );
                    try {
                        final QueryResult result = query.execute();
                        final RowIterator rowIterator = result.getRows();

                        if (rowIterator.hasNext()) {
                            data[3] = rowIterator.nextRow().getPath();
                            withResourcesWriter.writeNext(data);
                            LOGGER.info("  Adding resource from jcr query.");
                        } else {
                            LOGGER.info("  No resource found.");
                        }
                    } catch (InvalidQueryException ignored) {
                    }
                    
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Replace the input file with temp file
        try {
            Files.move(Paths.get(tempFilePath), Paths.get(filepath), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void clean(String filepath, String type, String skipUntilPrefix) {

        boolean dryRun = Boolean.parseBoolean(System.getProperty("dryrun"));

        String dryRunStr = dryRun ? ".dryrun" : "";

        if (dryRun) {
            LOGGER.warn("Running in DRY RUN mode -- will NOT unregister nodetype/namespaces.");
        }

        String statusFilePath = filepath + ".status";
        String completedFilePath;
        String rejectedFilePath;
        String skippedFilePath;

        if ("namespace".equalsIgnoreCase(type)) {
            if(hasSpuriousNodeTypeExists()) {
                LOGGER.error("Clean up splurious nodetypes before attempting namespace cleanup.");
                return;
            }
            buildNamespaceUriRelationships();
            statusFilePath += ".namespace" + dryRunStr;
            completedFilePath = filepath.replace(".csv", "-" + startTime + "-namespace-completed.csv" + dryRunStr);
            rejectedFilePath = filepath.replace(".csv", "-" + startTime + "-namespace-rejected.csv" + dryRunStr);
            skippedFilePath = filepath.replace(".csv", "-" + startTime + "-namespace-skipped.csv" + dryRunStr);
        } else {
            statusFilePath += ".nodetype" + dryRunStr;
            completedFilePath = filepath.replace(".csv", "-" + startTime + "-nodetype-completed.csv" + dryRunStr);
            rejectedFilePath = filepath.replace(".csv", "-" + startTime + "-nodetype-rejected.csv" + dryRunStr);
            skippedFilePath = filepath.replace(".csv", "-" + startTime + "-nodetype-skipped.csv" + dryRunStr);

        }

        if (skipUntilPrefix == null) {
            skipUntilPrefix = readLastProcessingPrefix(statusFilePath);
        }

        String[] data = {"namespace", "namespaceUri", "nodeType", "resource"};
        
        writeCSVLineToFile(completedFilePath, data, false);
        writeCSVLineToFile(rejectedFilePath, data, false);
        writeCSVLineToFile(skippedFilePath, data, false);

        boolean skipComplete = false;
        
        // Read namespaces from the input file
        try (CSVReader reader = new CSVReader(new FileReader(filepath))) {
            // Skip header row
            reader.skip(1);

            Iterator<String[]> csvRowIterator = reader.iterator();
            while (csvRowIterator.hasNext()) {
                data = csvRowIterator.next();
                LOGGER.info("Processing prefix: " + data[0] + ": ");
                
                if (skipUntilPrefix != null && ! skipComplete) {
                    if (skipUntilPrefix.equals(data[0])) {
                        LOGGER.info("Skip to target reached.");
                        skipComplete = true;
                    } else {
                        LOGGER.info("  Skipping prefix: " + data[0]);
                        writeCSVLineToFile(skippedFilePath, data, true);
                        continue;
                    }
                }

                // Write the current processing prefix to a file for resumability
                writeCurrentProcessingPrefix(statusFilePath, data[0]);


                if ("namespace".equalsIgnoreCase(type)) {
                    if (! doesNamespaceExists(data[0])) {
                        LOGGER.info("Namespace already unregistered.");
                        writeCSVLineToFile(skippedFilePath, data, true);
                        removeRelationships(data[1]);
                        continue;
                    }
                    if (hasChildNamespaceUris(data[1])) {
                        LOGGER.info("Rejecting - has child namespace URIs");
                        writeCSVLineToFile(rejectedFilePath, data, true);
                    } else if (doesNodeTypeExists(data[2])) {
                        LOGGER.info("  Cannot unregister namespace while corresponding nodeType still exists");
                        writeCSVLineToFile(rejectedFilePath, data, true);
                    } else {
                        if (! dryRun) {
                            try {
                                namespaceRegistry.unregisterNamespace(data[0]);
                            } catch (NamespaceException e) {
                                e.printStackTrace();
                            }
                        }
                        removeRelationships(data[1]);
                        writeCSVLineToFile(completedFilePath, data, true);
                        LOGGER.info(" Unregistered namespace: " + data[0]);
                    }
                } else {


                    final Query query = queryManager.createQuery(
                        "SELECT * FROM [" + data[2] + "]",
                        "JCR-SQL2"
                        );
                        
                    try {
                        final QueryResult result = query.execute();
                        final RowIterator rowIterator = result.getRows();

                        if (rowIterator.hasNext()) {  
                            LOGGER.info("Rejecting - has associated resources");
                            if (data[3] == null || "".equals(data[3])) {
                                data[3] = rowIterator.nextRow().getPath();
                                writeCSVLineToFile(rejectedFilePath, data, true);
                            } else {
                                writeCSVLineToFile(rejectedFilePath, data, true);
                            }
                        } else {
                            if (! dryRun) {
                                nodeTypeManager.unregisterNodeType(data[2]);
                            }
                            LOGGER.info(" Unregistered nodeType: " + data[2]);
                            session.save();
                            writeCSVLineToFile(completedFilePath, data, true);
                        }

                    } catch (InvalidQueryException e) {
                        e.printStackTrace();
                    }
                }
                
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String readLastProcessingPrefix(String statusFilePath) {
        try (BufferedReader reader = new BufferedReader(new FileReader(statusFilePath))) {
            LOGGER.info("Reading last processed prefix from status file.");
            return reader.readLine();
        } catch (IOException e) {
            // status file doesn't exist, start from the beginning
            return null;
        }
    }

    private static void writeCSVLineToFile(String filepath, String[] data, boolean appendMode) {
        try (CSVWriter writer = new CSVWriter(new FileWriter(filepath, appendMode))) {
            writer.writeNext(data);
        } catch (IOException e) {
            e.printStackTrace();
        }
            
    }

    private static void writeCurrentProcessingPrefix(String statusFilePath, String lastProcessingPrefix) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(statusFilePath))) {
            writer.write(lastProcessingPrefix);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean hasSpuriousNodeTypeExists() {
        boolean found = false;
        try {
            NodeTypeIterator nodeTypes = nodeTypeManager.getAllNodeTypes();
            while (nodeTypes.hasNext()) {
                String nodeTypeName = nodeTypes.nextNodeType().getName();
                if (nodeTypeName.startsWith("ns") && nodeTypeName.endsWith(":None")) {
                    LOGGER.info("Found spurious nodeTpe: " + nodeTypeName);
                    found = true;
                }
            }
        } catch(RepositoryException e) {
            e.printStackTrace();
        }
        return found;
    }

    private void buildNamespaceUriRelationships() {
        try {
            Set<String> namespaceURIs = Arrays.stream(namespaceRegistry.getURIs()).filter((k) -> k.contains("tx:")).collect(Collectors.toSet());
            LOGGER.info("Building namespace uri relationships");

            for(String uri : namespaceURIs) {
                LOGGER.debug("Building relationship for: " + uri);
                Set<String> parents;
                Set<String> children = new HashSet<String>();
                String trimmedUri = uri;
                for (int i = 0; i < 4; i++) {
                    trimmedUri = trimmedUri.substring(0, trimmedUri.lastIndexOf("/", trimmedUri.length()-2) + 1);
                    LOGGER.debug("  Checking for parent at: " + trimmedUri);
                    if (namespaceURIs.contains(trimmedUri)) {
                        LOGGER.debug("    Found. Adding parent/child relationships.");
                        parents = parentNamespaceUris.containsKey(uri) ? parentNamespaceUris.get(uri) : new HashSet<String>();
                        children = childNamespaceUris.containsKey(trimmedUri) ? childNamespaceUris.get(trimmedUri) : new HashSet<String>();
                        parents.add(trimmedUri);
                        children.add(uri);
                        parentNamespaceUris.put(uri, parents);
                        childNamespaceUris.put(trimmedUri, children);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        LOGGER.info("Building namespace uri relationships");
    }

    private boolean hasChildNamespaceUris(String uri) {
        return (childNamespaceUris.get(uri) != null) && ! childNamespaceUris.get(uri).isEmpty(); 
    }

    private void removeRelationships(String uri) {
        LOGGER.debug(" Removing relationship for: " + uri);
        if (parentNamespaceUris.get(uri) == null) {
            return;
        }
        for(String parent : parentNamespaceUris.get(uri)) {
            childNamespaceUris.get(parent).remove(uri);
            LOGGER.debug("  Removed from: " + parent);
        }
    }

    private boolean doesNodeTypeExists(String nodeType) throws RepositoryException {
        boolean exists = false;
        try {
            exists = nodeTypeManager.hasNodeType(nodeType);
        } catch(ValueFormatException e) {
            e.printStackTrace();
        }
        return exists;
    }

    private boolean doesNamespaceExists(String prefix) throws RepositoryException {
        boolean exists = false;
        try {
            exists = ! namespaceRegistry.getURI(prefix).isEmpty();
        } catch(Exception e) {
            e.printStackTrace();
        }
        return exists;
    }
}

