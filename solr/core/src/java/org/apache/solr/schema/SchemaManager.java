/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.solr.cloud.ZkController;
import org.apache.solr.cloud.ZkSolrResourceLoader;
import org.apache.solr.common.SolrException;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.rest.BaseSolrResource;
import org.apache.solr.util.CommandOperation;
import org.apache.solr.util.TimeOut;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.apache.solr.schema.FieldType.CLASS_NAME;
import static org.apache.solr.schema.IndexSchema.DESTINATION;
import static org.apache.solr.schema.IndexSchema.MAX_CHARS;
import static org.apache.solr.schema.IndexSchema.NAME;
import static org.apache.solr.schema.IndexSchema.SOURCE;
import static org.apache.solr.schema.IndexSchema.TYPE;

/**
 * A utility class to manipulate schema using the bulk mode.
 * This class takes in all the commands and processes them completely.
 * It is an all or nothing operation.
 */
public class SchemaManager {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  final SolrQueryRequest req;
  ManagedIndexSchema managedIndexSchema;

  public SchemaManager(SolrQueryRequest req){
    this.req = req;
  }

  /**
   * Take in a JSON command set and execute them. It tries to capture as many errors
   * as possible instead of failing at the first error it encounters
   * @param reader The input as a Reader
   * @return List of errors. If the List is empty then the operation was successful.
   */
  public List performOperations(Reader reader) throws Exception {
    List<CommandOperation> ops;
    try {
      ops = CommandOperation.parse(reader);
    } catch (Exception e) {
      String msg = "Error parsing schema operations ";
      log.warn(msg, e);
      return Collections.singletonList(singletonMap(CommandOperation.ERR_MSGS, msg + ":" + e.getMessage()));
    }
    List errs = CommandOperation.captureErrors(ops);
    if (!errs.isEmpty()) return errs;

    IndexSchema schema = req.getCore().getLatestSchema();
    if (schema instanceof ManagedIndexSchema && schema.isMutable()) {
      synchronized (schema.getSchemaUpdateLock()) {
        return doOperations(ops);
      }
    } else {
      return singletonList(singletonMap(CommandOperation.ERR_MSGS, "schema is not editable"));
    }
  }

  private List doOperations(List<CommandOperation> operations) throws InterruptedException, IOException, KeeperException {
    //The default timeout is 10 minutes when no BaseSolrResource.UPDATE_TIMEOUT_SECS is specified
    int timeout = req.getParams().getInt(BaseSolrResource.UPDATE_TIMEOUT_SECS, 600);

    //If BaseSolrResource.UPDATE_TIMEOUT_SECS=0 or -1 then end time then we'll try for 10 mins ( default timeout )
    if (timeout < 1) {
      timeout = 600;
    }
    TimeOut timeOut = new TimeOut(timeout, TimeUnit.SECONDS);
    SolrCore core = req.getCore();
    String errorMsg = "Unable to persist managed schema. ";
    while (!timeOut.hasTimedOut()) {
      managedIndexSchema = getFreshManagedSchema(req.getCore());
      for (CommandOperation op : operations) {
        OpType opType = OpType.get(op.name);
        if (opType != null) {
          opType.perform(op, this);
        } else {
          op.addError("No such operation : " + op.name);
        }
      }
      List errs = CommandOperation.captureErrors(operations);
      if (!errs.isEmpty()) return errs;
      SolrResourceLoader loader = req.getCore().getResourceLoader();
      if (loader instanceof ZkSolrResourceLoader) {
        ZkSolrResourceLoader zkLoader = (ZkSolrResourceLoader) loader;
        StringWriter sw = new StringWriter();
        try {
          managedIndexSchema.persist(sw);
        } catch (IOException e) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "unable to serialize schema");
          //unlikely
        }

        try {
          int latestVersion = ZkController.persistConfigResourceToZooKeeper(zkLoader, managedIndexSchema.getSchemaZkVersion(),
              managedIndexSchema.getResourceName(), sw.toString().getBytes(StandardCharsets.UTF_8), true);
          req.getCore().getCoreDescriptor().getCoreContainer().reload(req.getCore().getName());
          waitForOtherReplicasToUpdate(timeOut, latestVersion);
          return Collections.emptyList();
        } catch (ZkController.ResourceModifiedInZkException e) {
          log.info("Schema was modified by another node. Retrying..");
        }
      } else {
        try {
          //only for non cloud stuff
          managedIndexSchema.persistManagedSchema(false);
          core.setLatestSchema(managedIndexSchema);
          return Collections.emptyList();
        } catch (SolrException e) {
          log.warn(errorMsg);
          return singletonList(errorMsg + e.getMessage());
        }
      }
    }
    log.warn(errorMsg + "Timed out.");
    return singletonList(errorMsg + "Timed out.");
  }

  private void waitForOtherReplicasToUpdate(TimeOut timeOut, int latestVersion) {
    CoreDescriptor cd = req.getCore().getCoreDescriptor();
    String collection = cd.getCollectionName();
    if (collection != null) {
      ZkSolrResourceLoader zkLoader = (ZkSolrResourceLoader) managedIndexSchema.getResourceLoader();
      if (timeOut.hasTimedOut()) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
            "Not enough time left to update replicas. However, the schema is updated already.");
      }
      ManagedIndexSchema.waitForSchemaZkVersionAgreement(collection, cd.getCloudDescriptor().getCoreNodeName(),
          latestVersion, zkLoader.getZkController(), (int) timeOut.timeLeft(TimeUnit.SECONDS));
    }
  }

  public enum OpType {
    ADD_FIELD_TYPE("add-field-type") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String name = op.getStr(NAME);
        String className = op.getStr(CLASS_NAME);
        if (op.hasError())
          return false;
        try {
          FieldType fieldType = mgr.managedIndexSchema.newFieldType(name, className, op.getDataMap());
          mgr.managedIndexSchema = mgr.managedIndexSchema.addFieldTypes(singletonList(fieldType), false);
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    },
    ADD_COPY_FIELD("add-copy-field") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String src  = op.getStr(SOURCE);
        List<String> dests = op.getStrs(DESTINATION);

        int maxChars = CopyField.UNLIMITED; // If maxChars is not specified, there is no limit on copied chars
        String maxCharsStr = op.getStr(MAX_CHARS, null);
        if (null != maxCharsStr) {
          try {
            maxChars = Integer.parseInt(maxCharsStr);
          } catch (NumberFormatException e) {
            op.addError("Exception parsing " + MAX_CHARS + " '" + maxCharsStr + "': " + getErrorStr(e));
          }
          if (maxChars < 0) {
            op.addError(MAX_CHARS + " '" + maxCharsStr + "' is negative.");
          }
        }

        if (op.hasError())
          return false;
        if ( ! op.getValuesExcluding(SOURCE, DESTINATION, MAX_CHARS).isEmpty()) {
          op.addError("Only the '" + SOURCE + "', '" + DESTINATION + "' and '" + MAX_CHARS
              + "' params are allowed with the 'add-copy-field' operation");
          return false;
        }
        try {
          mgr.managedIndexSchema = mgr.managedIndexSchema.addCopyFields(src, dests, maxChars);
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    },
    ADD_FIELD("add-field") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String name = op.getStr(NAME);
        String type = op.getStr(TYPE);
        if (op.hasError())
          return false;
        try {
          SchemaField field = mgr.managedIndexSchema.newField(name, type, op.getValuesExcluding(NAME, TYPE));
          mgr.managedIndexSchema
              = mgr.managedIndexSchema.addFields(singletonList(field), Collections.emptyMap(), false);
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    },
    ADD_DYNAMIC_FIELD("add-dynamic-field") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String name = op.getStr(NAME);
        String type = op.getStr(TYPE);
        if (op.hasError())
          return false;
        try {
          SchemaField field = mgr.managedIndexSchema.newDynamicField(name, type, op.getValuesExcluding(NAME, TYPE));
          mgr.managedIndexSchema
              = mgr.managedIndexSchema.addDynamicFields(singletonList(field), Collections.emptyMap(), false);
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    },
    DELETE_FIELD_TYPE("delete-field-type") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String name = op.getStr(NAME);
        if (op.hasError())
          return false;
        if ( ! op.getValuesExcluding(NAME).isEmpty()) {
          op.addError("Only the '" + NAME + "' param is allowed with the 'delete-field-type' operation");
          return false;
        }
        try {
          mgr.managedIndexSchema = mgr.managedIndexSchema.deleteFieldTypes(singleton(name));
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    },
    DELETE_COPY_FIELD("delete-copy-field") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String source = op.getStr(SOURCE);
        List<String> dests = op.getStrs(DESTINATION);
        if (op.hasError())
          return false;
        if ( ! op.getValuesExcluding(SOURCE, DESTINATION).isEmpty()) {
          op.addError("Only the '" + SOURCE + "' and '" + DESTINATION
              + "' params are allowed with the 'delete-copy-field' operation");
          return false;
        }
        try {
          mgr.managedIndexSchema = mgr.managedIndexSchema.deleteCopyFields(singletonMap(source, dests));
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    },
    DELETE_FIELD("delete-field") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String name = op.getStr(NAME);
        if (op.hasError())
          return false;
        if ( ! op.getValuesExcluding(NAME).isEmpty()) {
          op.addError("Only the '" + NAME + "' param is allowed with the 'delete-field' operation");
          return false;
        }
        try {
          mgr.managedIndexSchema = mgr.managedIndexSchema.deleteFields(singleton(name));
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    },
    DELETE_DYNAMIC_FIELD("delete-dynamic-field") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String name = op.getStr(NAME);
        if (op.hasError())
          return false;
        if ( ! op.getValuesExcluding(NAME).isEmpty()) {
          op.addError("Only the '" + NAME + "' param is allowed with the 'delete-dynamic-field' operation");
          return false;
        }
        try {
          mgr.managedIndexSchema = mgr.managedIndexSchema.deleteDynamicFields(singleton(name));
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    },
    REPLACE_FIELD_TYPE("replace-field-type") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String name = op.getStr(NAME);
        String className = op.getStr(CLASS_NAME);
        if (op.hasError())
          return false;
        try {
          mgr.managedIndexSchema = mgr.managedIndexSchema.replaceFieldType(name, className, op.getDataMap());
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    },
    REPLACE_FIELD("replace-field") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String name = op.getStr(NAME);
        String type = op.getStr(TYPE);
        if (op.hasError())
          return false;
        FieldType ft = mgr.managedIndexSchema.getFieldTypeByName(type);
        if (ft == null) {
          op.addError("No such field type '" + type + "'");
          return false;
        }
        try {
          mgr.managedIndexSchema = mgr.managedIndexSchema.replaceField(name, ft, op.getValuesExcluding(NAME, TYPE));
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    },
    REPLACE_DYNAMIC_FIELD("replace-dynamic-field") {
      @Override public boolean perform(CommandOperation op, SchemaManager mgr) {
        String name = op.getStr(NAME);
        String type = op.getStr(TYPE);
        if (op.hasError())
          return false;
        FieldType ft = mgr.managedIndexSchema.getFieldTypeByName(type);
        if (ft == null) {
          op.addError("No such field type '" + type + "'");
          return  false;
        }
        try {
          mgr.managedIndexSchema = mgr.managedIndexSchema.replaceDynamicField(name, ft, op.getValuesExcluding(NAME, TYPE));
          return true;
        } catch (Exception e) {
          op.addError(getErrorStr(e));
          return false;
        }
      }
    };

    public abstract boolean perform(CommandOperation op, SchemaManager mgr);

    public static OpType get(String label) {
      return Nested.OP_TYPES.get(label);
    }

    private static class Nested { // Initializes contained static map before any enum ctor
      static final Map<String,OpType> OP_TYPES = new HashMap<>();
    }

    private OpType(String label) {
      Nested.OP_TYPES.put(label, this);
    }
  }

  public static String getErrorStr(Exception e) {
    StringBuilder sb = new StringBuilder();
    Throwable cause = e;
    for (int i = 0 ; i < 5 ; i++) {
      sb.append(cause.getMessage()).append("\n");
      if (cause.getCause() == null || cause.getCause() == cause) break;
      cause = cause.getCause();
    }
    return sb.toString();
  }

  public static ManagedIndexSchema getFreshManagedSchema(SolrCore core) throws IOException,
      KeeperException, InterruptedException {

    SolrResourceLoader resourceLoader = core.getResourceLoader();
    String name = core.getLatestSchema().getResourceName();
    if (resourceLoader instanceof ZkSolrResourceLoader) {
      InputStream in = resourceLoader.openResource(name);
      if (in instanceof ZkSolrResourceLoader.ZkByteArrayInputStream) {
        int version = ((ZkSolrResourceLoader.ZkByteArrayInputStream) in).getStat().getVersion();
        log.info("managed schema loaded . version : {} ", version);
        return new ManagedIndexSchema(core.getSolrConfig(), name, new InputSource(in), true, name, version,
            core.getLatestSchema().getSchemaUpdateLock());
      } else {
        return (ManagedIndexSchema) core.getLatestSchema();
      }
    } else {
      return (ManagedIndexSchema) core.getLatestSchema();
    }
  }
}
