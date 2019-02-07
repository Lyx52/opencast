/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.workflow.handler.workflow;

import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.Catalog;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.util.MimeType;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workflow.api.WorkflowOperationResult.Action;
import org.opencastproject.workspace.api.Workspace;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * This WorkflowOperationHandler adds an configurable catalog to the MediaPackage.
 * It supports the following workflow configuration keys:
 *   catalog-path the path of the catalog to add;
 *   catalog-flavor the flavor of the catalog, used to identify catalogs of the same type;
 *   catalog-name name of the catalog in the workspace;
 *   catalog-tags list of comma seperated catalog tags;
 *   catalog-type-collision-behavior the action to perform, if an catalog of the same flavor already exists,
 *     three options are supported: 'skip' the adding of the catalog, 'fail' the workflow operation or 'keep'
 *     the new catalog, resulting in two or more catalogs of the same type coexisting
 */
public class AddCatalogWorkflowOperationHandler extends AbstractWorkflowOperationHandler {

  /** enum used to specify the behavior on detecting a catalog type collision */
  private enum CatalogTypeCollisionBehavior {
    SKIP, KEEP, FAIL
  }

  /** The logging facility */
  private static final Logger logger = LoggerFactory
    .getLogger(AddCatalogWorkflowOperationHandler.class);

  /** config key for the catalog name */
  private static final String CFG_KEY_CATALOG_NAME   = "catalog-name";
  /** config key for the catalog flavor */
  private static final String CFG_KEY_CATALOG_FLAVOR = "catalog-flavor";
  /** config key, which locates the catalog on the filesystem */
  private static final String CFG_KEY_CATALOG_PATH   = "catalog-path";
  /** config key for the catalog tags */
  private static final String CFG_KEY_CATALOG_TAGS   = "catalog-tags";
  /** config key, which defines the behavior if a catalog of the same flavor already exists */
  private static final String CFG_KEY_CATALOG_TYPE_COLLISION_BEHAVIOR = "catalog-type-collision-behavior";

  /** The mimetype of the added catalogs */
  private static final MimeType CATALOG_MIME_TYPE = MimeType.mimeType("text", "xml");

  /** The workspace, where the catalog files are put. */
  private Workspace workspace;

  /**
   * Sets the workspace to use.
   *
   * @param workspace
   *          the workspace
   */
  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public WorkflowOperationResult start(WorkflowInstance wInst, JobContext context)
      throws WorkflowOperationException {
    // get Workflow configuration
    String catalogName = getCfgValueFromOp(wInst, CFG_KEY_CATALOG_NAME, false);
    String catalogPath = getCfgValueFromOp(wInst, CFG_KEY_CATALOG_PATH, false);
    String catalogTags = getCfgValueFromOp(wInst, CFG_KEY_CATALOG_TAGS, true);
    MediaPackageElementFlavor    catalogFlavor = parseFlavor(
                           getCfgValueFromOp(wInst, CFG_KEY_CATALOG_FLAVOR, false));
    CatalogTypeCollisionBehavior collBehavior  = parseCollisionBehavior(
                           getCfgValueFromOp(wInst, CFG_KEY_CATALOG_TYPE_COLLISION_BEHAVIOR, false));

    MediaPackage mp = wInst.getMediaPackage();

    // if CatalogType is already part of the MediaPackage handle special cases
    if (doesCatalogFlavorExist(catalogFlavor, mp.getCatalogs())) {
      if (collBehavior == CatalogTypeCollisionBehavior.FAIL) {
        throw new WorkflowOperationException("Catalog Type already exists and 'fail' was specified");
      }
      else if (collBehavior == CatalogTypeCollisionBehavior.SKIP) {
        // don't add the Catalog
        return createResult(mp, Action.CONTINUE);
      }
    }

    // 'upload Catalog' to workspace
    File   catalogFile = new File(catalogPath);
    String catalogId   = UUID.randomUUID().toString();
    URI    catalogURI  = null;
    try (InputStream catalogInputStream = FileUtils.openInputStream(catalogFile)) {
      catalogURI = workspace.put(mp.getIdentifier().toString(), catalogId,
              catalogName, catalogInputStream);
    }
    catch (IOException e) {
      throw new WorkflowOperationException(e);
    }

    // add Catalog to MediaPackage (and set Properties)
    MediaPackageElement mpe = mp.add(catalogURI, MediaPackageElement.Type.Catalog, catalogFlavor);
    mpe.setIdentifier(catalogId);
    mpe.setMimeType(CATALOG_MIME_TYPE);
    if (catalogTags != null) {
      for (String tag : catalogTags.split(",")) {
        mpe.addTag(tag);
      }
    }

    return createResult(mp, Action.CONTINUE);
  }

  /**
   * Checks whether the catalogFlavor exists in the array of catalogs
   *
   * @param catalogFlavor
   * @param catalogs
   * @return true, if the catalogFlavor exists in the array of catalogs, else false
   */
  private boolean doesCatalogFlavorExist(MediaPackageElementFlavor catalogFlavor, Catalog[] catalogs) {
    List<Catalog> ctls = Arrays.asList(catalogs);

    boolean doesFlavorExist = ctls.stream()
      .anyMatch(cat -> catalogFlavor.matches(cat.getFlavor()));

    return doesFlavorExist;
  }

  /**
   * Gets a configuration value for a specific key from the current operation.
   * isOptional can be used to specify whether null values are allowed to be returned
   * or whether an WorkflowOperationException should be thrown.
   *
   * @param inst
   *          used to get the value of the configuration
   * @param cfgKey
   *          specifies the value to get
   * @param isOptional
   *          is the configuration value allowed to be not set
   * @return the value of the configuration key or null if isOptional is set and the configuration value is not set
   * @throws WorkflowOperationException
   *           if isOptional is false and the configuration value is not set
   */
  private String getCfgValueFromOp(WorkflowInstance inst, String cfgKey, boolean isOptional)
      throws WorkflowOperationException {
    String value = inst.getCurrentOperation().getConfiguration(cfgKey);

    if (!isOptional && (value == null || value.isEmpty())) {
      throw new WorkflowOperationException("'" + cfgKey + "' not set");
    }

    return value;
  }

  /**
   * Parses the rawFlavor String to an MediaPackageElementFlavor
   * or throws an WorkflowOperationException if the parsing failed
   *
   * @param rawFlavor
   * @return
   * @throws WorkflowOperationException
   */
  private MediaPackageElementFlavor parseFlavor(String rawFlavor)
      throws WorkflowOperationException {
    try {
      return MediaPackageElementFlavor.parseFlavor(rawFlavor);
    }
    catch (IllegalArgumentException e) {
      throw new WorkflowOperationException("Flavor needs to be an correct Flavor MimeType");
    }
  }

  /**
   * Parses the rawBehavior String into an CatalogTypeCollisionBehavior.
   * Throws an WorkflowOperationException if the String couldn't be parsed.
   *
   * @param rawBehavior
   * @return
   * @throws WorkflowOperationException
   */
  private CatalogTypeCollisionBehavior parseCollisionBehavior(String rawBehavior)
      throws WorkflowOperationException {
    try {
      return CatalogTypeCollisionBehavior.valueOf(rawBehavior.toUpperCase());
    }
    catch (IllegalArgumentException e) {
      throw new WorkflowOperationException("Workflowoperation configured incorrectly, the configuration '"
                                           + CFG_KEY_CATALOG_TYPE_COLLISION_BEHAVIOR
                                           + "' only accepts 'skip', 'keep', 'fail'");
    }
  }
}
