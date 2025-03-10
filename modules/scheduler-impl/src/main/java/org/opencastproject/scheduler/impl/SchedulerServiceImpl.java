/*
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
package org.opencastproject.scheduler.impl;

import static com.entwinemedia.fn.Stream.$;
import static com.entwinemedia.fn.data.Opt.some;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.opencastproject.scheduler.impl.SchedulerUtil.calculateChecksum;
import static org.opencastproject.scheduler.impl.SchedulerUtil.episodeToMp;
import static org.opencastproject.scheduler.impl.SchedulerUtil.eventOrganizationFilter;
import static org.opencastproject.scheduler.impl.SchedulerUtil.isNotEpisodeDublinCore;
import static org.opencastproject.scheduler.impl.SchedulerUtil.recordToMp;
import static org.opencastproject.scheduler.impl.SchedulerUtil.uiAdapterToFlavor;
import static org.opencastproject.security.api.SecurityConstants.GLOBAL_ADMIN_ROLE;
import static org.opencastproject.util.EqualsUtil.ne;
import static org.opencastproject.util.RequireUtil.notEmpty;
import static org.opencastproject.util.RequireUtil.notNull;
import static org.opencastproject.util.RequireUtil.requireTrue;
import static org.opencastproject.util.data.Monadics.mlist;

import org.opencastproject.assetmanager.api.Asset;
import org.opencastproject.assetmanager.api.AssetManager;
import org.opencastproject.assetmanager.api.Availability;
import org.opencastproject.assetmanager.api.Snapshot;
import org.opencastproject.assetmanager.api.query.AQueryBuilder;
import org.opencastproject.assetmanager.api.query.ARecord;
import org.opencastproject.assetmanager.api.query.AResult;
import org.opencastproject.assetmanager.api.query.ASelectQuery;
import org.opencastproject.assetmanager.api.query.Predicate;
import org.opencastproject.elasticsearch.api.SearchIndexException;
import org.opencastproject.elasticsearch.index.ElasticsearchIndex;
import org.opencastproject.elasticsearch.index.objects.event.Event;
import org.opencastproject.elasticsearch.index.objects.event.EventIndexUtils;
import org.opencastproject.elasticsearch.index.rebuild.AbstractIndexProducer;
import org.opencastproject.elasticsearch.index.rebuild.IndexProducer;
import org.opencastproject.elasticsearch.index.rebuild.IndexRebuildException;
import org.opencastproject.elasticsearch.index.rebuild.IndexRebuildService;
import org.opencastproject.mediapackage.Catalog;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.MediaPackageElements;
import org.opencastproject.mediapackage.MediaPackageException;
import org.opencastproject.mediapackage.MediaPackageSupport;
import org.opencastproject.mediapackage.identifier.Id;
import org.opencastproject.mediapackage.identifier.IdImpl;
import org.opencastproject.message.broker.api.scheduler.SchedulerItem;
import org.opencastproject.message.broker.api.scheduler.SchedulerItemList;
import org.opencastproject.message.broker.api.update.SchedulerUpdateHandler;
import org.opencastproject.metadata.dublincore.DCMIPeriod;
import org.opencastproject.metadata.dublincore.DublinCore;
import org.opencastproject.metadata.dublincore.DublinCoreCatalog;
import org.opencastproject.metadata.dublincore.DublinCoreUtil;
import org.opencastproject.metadata.dublincore.DublinCoreValue;
import org.opencastproject.metadata.dublincore.DublinCores;
import org.opencastproject.metadata.dublincore.EncodingSchemeUtils;
import org.opencastproject.metadata.dublincore.EventCatalogUIAdapter;
import org.opencastproject.metadata.dublincore.Precision;
import org.opencastproject.scheduler.api.Recording;
import org.opencastproject.scheduler.api.RecordingImpl;
import org.opencastproject.scheduler.api.RecordingState;
import org.opencastproject.scheduler.api.SchedulerConflictException;
import org.opencastproject.scheduler.api.SchedulerException;
import org.opencastproject.scheduler.api.SchedulerService;
import org.opencastproject.scheduler.api.TechnicalMetadata;
import org.opencastproject.scheduler.api.TechnicalMetadataImpl;
import org.opencastproject.scheduler.api.Util;
import org.opencastproject.scheduler.impl.persistence.ExtendedEventDto;
import org.opencastproject.security.api.AccessControlList;
import org.opencastproject.security.api.AccessControlParser;
import org.opencastproject.security.api.AccessControlUtil;
import org.opencastproject.security.api.AuthorizationService;
import org.opencastproject.security.api.Organization;
import org.opencastproject.security.api.OrganizationDirectoryService;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.UnauthorizedException;
import org.opencastproject.security.api.User;
import org.opencastproject.security.util.SecurityUtil;
import org.opencastproject.series.api.SeriesService;
import org.opencastproject.util.DateTimeSupport;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.util.OsgiUtil;
import org.opencastproject.util.XmlNamespaceBinding;
import org.opencastproject.util.XmlNamespaceContext;
import org.opencastproject.util.data.Option;
import org.opencastproject.util.data.functions.Misc;
import org.opencastproject.util.data.functions.Strings;
import org.opencastproject.workspace.api.Workspace;

import com.entwinemedia.fn.Fn;
import com.entwinemedia.fn.Stream;
import com.entwinemedia.fn.data.Opt;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.property.RRule;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implementation of {@link SchedulerService}.
 */
@Component(
    immediate = true,
    service = { ManagedService.class, SchedulerService.class, IndexProducer.class },
    property = {
        "service.description=Scheduler Service"
    }
)
public class SchedulerServiceImpl extends AbstractIndexProducer implements SchedulerService, ManagedService {

  /** The logger */
  private static final Logger logger = LoggerFactory.getLogger(SchedulerServiceImpl.class);

  /** The last modified cache configuration key */
  private static final String CFG_KEY_LAST_MODIFIED_CACHE_EXPIRE = "last_modified_cache_expire";

  /** The maintenance configuration key */
  private static final String CFG_KEY_MAINTENANCE = "maintenance";

  /** The default cache expire time in seconds */
  private static final int DEFAULT_CACHE_EXPIRE = 60;

  /** The Etag for an empty calendar */
  private static final String EMPTY_CALENDAR_ETAG = "mod0";

  private static final String SNAPSHOT_OWNER = SchedulerService.JOB_TYPE;

  private static final Gson gson = new Gson();

  /**
   * Deserializes properties stored in string columns of the extended event table
   * @param props Properties as retrieved from the DB
   * @return deserialized key-value pairs
   */
  private static Map<String,String> deserializeExtendedEventProperties(String props) {
    if (props == null || props.trim().isEmpty()) {
      return new HashMap<>();
    }
    Type type = new TypeToken<Map<String, String>>() { }.getType();
    return gson.fromJson(props, type);
  }

  /** The last modified cache */
  protected Cache<String, String> lastModifiedCache = CacheBuilder.newBuilder()
          .expireAfterWrite(DEFAULT_CACHE_EXPIRE, TimeUnit.SECONDS).build();

  /** Persistent storage for events */
  private SchedulerServiceDatabase persistence;

  /** The series service */
  private SeriesService seriesService;

  /** The security service used to run the security context with. */
  private SecurityService securityService;

  /** The asset manager */
  private AssetManager assetManager;

  /** The workspace */
  private Workspace workspace;

  /** The authorization service */
  private AuthorizationService authorizationService;

  /** The organization directory service */
  private OrganizationDirectoryService orgDirectoryService;

  /** The Elasticsearch indices */
  private ElasticsearchIndex index;

  /** The list of registered event catalog UI adapters */
  private List<EventCatalogUIAdapter> eventCatalogUIAdapters = new ArrayList<>();
  private final List<SchedulerUpdateHandler> schedulerUpdateHandlers = new CopyOnWriteArrayList<>();

  /** The system user name */
  private String systemUserName;

  private ComponentContext componentContext;

  /**
   * OSGi callback to add an update handler.
   *
   * @param handler
   */
  @Reference(
      cardinality = ReferenceCardinality.MULTIPLE,
      policy = ReferencePolicy.DYNAMIC,
      unbind = "removeSchedulerUpdateHandler"
  )
  public void addSchedulerUpdateHandler(SchedulerUpdateHandler handler) {
    this.schedulerUpdateHandlers.add(handler);
  }

  public void removeSchedulerUpdateHandler(SchedulerUpdateHandler handler) {
    this.schedulerUpdateHandlers.remove(handler);
  }

  /**
   * OSGi callback to set Persistence Service.
   *
   * @param persistence
   */
  @Reference
  public void setPersistence(SchedulerServiceDatabase persistence) {
    this.persistence = persistence;
  }

  /**
   * OSGi callback for setting Series Service.
   *
   * @param seriesService
   */
  @Reference
  public void setSeriesService(SeriesService seriesService) {
    this.seriesService = seriesService;
  }

  /**
   * OSGi callback to set security service.
   *
   * @param securityService
   */
  @Reference
  public void setSecurityService(SecurityService securityService) {
    this.securityService = securityService;
  }

  /**
   * OSGi callback to set the asset manager.
   *
   * @param assetManager
   */
  @Reference
  public void setAssetManager(AssetManager assetManager) {
    this.assetManager = assetManager;
  }

  /**
   * OSGi callback to set the workspace.
   *
   * @param workspace
   */
  @Reference
  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  /**
   * OSGi callback to set the authorization service.
   *
   * @param authorizationService
   */
  @Reference
  public void setAuthorizationService(AuthorizationService authorizationService) {
    this.authorizationService = authorizationService;
  }

  /**
   * Update all of the handlers that an event has changed
   *
   * @param list The list of scheduler changes for a mediapackage
   */
  private void sendSchedulerUpdate(SchedulerItemList list) {
    while (schedulerUpdateHandlers.size() != 1) {
      logger.warn("Expecting 1 handler, but {} are registered.  Waiting 10s then retrying...", schedulerUpdateHandlers.size());
      try {
        Thread.sleep(10000L);
      } catch (InterruptedException e) { /* swallow this, nothing to do */ }
    }
    String mpid = list.getId();
    for (SchedulerItem item : list.getItems()) {
      for (SchedulerUpdateHandler handler : this.schedulerUpdateHandlers) {
        handler.execute(mpid, item);
      }
    }
  }

  /**
   * OSGi callback to set the organization directory service.
   *
   * @param orgDirectoryService
   */
  @Reference
  public void setOrgDirectoryService(OrganizationDirectoryService orgDirectoryService) {
    this.orgDirectoryService = orgDirectoryService;
  }

  /**
   * OSgi callback to set the Elasticsearch index.
   *
   * @param index
   *          the Elasticsearch index.
   */
  @Reference
  public void setIndex(ElasticsearchIndex index) {
    this.index = index;
  }

  /** OSGi callback to add {@link EventCatalogUIAdapter} instance. */
  @Reference(
      cardinality = ReferenceCardinality.MULTIPLE,
      policy = ReferencePolicy.DYNAMIC,
      unbind = "removeCatalogUIAdapter"
  )
  public void addCatalogUIAdapter(EventCatalogUIAdapter catalogUIAdapter) {
    eventCatalogUIAdapters.add(catalogUIAdapter);
  }

  /** OSGi callback to remove {@link EventCatalogUIAdapter} instance. */
  public void removeCatalogUIAdapter(EventCatalogUIAdapter catalogUIAdapter) {
    eventCatalogUIAdapters.remove(catalogUIAdapter);
  }

  /**
   * Activates Scheduler Service.
   *
   * @param cc
   *          ComponentContext
   * @throws Exception
   */
  @Activate
  public void activate(ComponentContext cc) throws Exception {
    this.componentContext = cc;
    systemUserName = SecurityUtil.getSystemUserName(cc);
    logger.info("Activating Scheduler Service");
  }

  @Override
  public void updated(Dictionary<String, ?> properties) throws ConfigurationException {
    if (properties != null) {
      final Option<Integer> cacheExpireDuration = OsgiUtil.getOptCfg(properties, CFG_KEY_LAST_MODIFIED_CACHE_EXPIRE)
              .bind(Strings.toInt);
      if (cacheExpireDuration.isSome()) {
        lastModifiedCache = CacheBuilder.newBuilder().expireAfterWrite(cacheExpireDuration.get(), TimeUnit.SECONDS)
                .build();
        logger.info("Set last modified cache to {}", DateTimeSupport.humanReadableTime(cacheExpireDuration.get()));
      } else {
        logger.info("Set last modified cache to default {}", DateTimeSupport.humanReadableTime(DEFAULT_CACHE_EXPIRE));
      }
      final Option<Boolean> maintenance = OsgiUtil.getOptCfgAsBoolean(properties, CFG_KEY_MAINTENANCE);
      if (maintenance.getOrElse(false)) {
        final String name = SchedulerServiceImpl.class.getName();
        logger.warn("Putting scheduler into maintenance mode. This only makes sense when migrating data. If this is not"
                + " intended, edit the config file '{}.cfg' accordingly and restart opencast.", name);
        componentContext.disableComponent(name);
      }
    }
  }

  @Override
  public void addEvent(Date startDateTime, Date endDateTime, String captureAgentId, Set<String> userIds,
          MediaPackage mediaPackage, Map<String, String> wfProperties, Map<String, String> caMetadata,
          Opt<String> schedulingSource)
                  throws UnauthorizedException, SchedulerException {
    addEventInternal(startDateTime, endDateTime, captureAgentId, userIds, mediaPackage, wfProperties, caMetadata,
            schedulingSource);
  }

  private void addEventInternal(Date startDateTime, Date endDateTime, String captureAgentId, Set<String> userIds,
          MediaPackage mediaPackage, Map<String, String> wfProperties, Map<String, String> caMetadata,
          Opt<String> schedulingSource)
                  throws SchedulerException {
    notNull(startDateTime, "startDateTime");
    notNull(endDateTime, "endDateTime");
    notEmpty(captureAgentId, "captureAgentId");
    notNull(userIds, "userIds");
    notNull(mediaPackage, "mediaPackage");
    notNull(wfProperties, "wfProperties");
    notNull(caMetadata, "caMetadata");
    notNull(schedulingSource, "schedulingSource");
    if (endDateTime.before(startDateTime))
      throw new IllegalArgumentException("The end date is before the start date");

    final String mediaPackageId = mediaPackage.getIdentifier().toString();

    try {
      AQueryBuilder query = assetManager.createQuery();
      AResult result = query.select(query.nothing())
              .where(withOrganization(query).and(query.mediaPackageId(mediaPackageId).and(query.version().isLatest())))
              .run();
      Opt<ARecord> record = result.getRecords().head();
      if (record.isSome()) {
        logger.warn("Mediapackage with id '{}' already exists!", mediaPackageId);
        throw new SchedulerConflictException("Mediapackage with id '" + mediaPackageId + "' already exists!");
      }

      Opt<String> seriesId = Opt.nul(StringUtils.trimToNull(mediaPackage.getSeries()));

      List<MediaPackage> conflictingEvents = findConflictingEvents(captureAgentId, startDateTime, endDateTime);
      if (conflictingEvents.size() > 0) {
        logger.info("Unable to add event {}, conflicting events found: {}", mediaPackageId, conflictingEvents);
        throw new SchedulerConflictException(
                "Unable to add event, conflicting events found for event " + mediaPackageId);
      }

      // Load dublincore and acl for update
      Opt<DublinCoreCatalog> dublinCore = DublinCoreUtil.loadEpisodeDublinCore(workspace, mediaPackage)
          .map(Opt::some).orElse(Opt.none());
      AccessControlList acl = authorizationService.getActiveAcl(mediaPackage).getA();

      // Get updated agent properties
      Map<String, String> finalCaProperties = getFinalAgentProperties(caMetadata, wfProperties, captureAgentId,
              seriesId, dublinCore);

      // Persist asset
      String checksum = calculateChecksum(workspace, getEventCatalogUIAdapterFlavors(), startDateTime, endDateTime,
                                          captureAgentId, userIds, mediaPackage, dublinCore, wfProperties,
                                          finalCaProperties, acl);
      persistEvent(mediaPackageId, checksum, Opt.some(startDateTime), Opt.some(endDateTime),
              Opt.some(captureAgentId), Opt.some(userIds), Opt.some(mediaPackage), Opt.some(wfProperties),
              Opt.some(finalCaProperties), schedulingSource);

      // Update live event
      updateLiveEvent(mediaPackageId, Opt.some(acl), dublinCore, Opt.some(startDateTime),
              Opt.some(endDateTime), Opt.some(captureAgentId), Opt.some(finalCaProperties));

      // Update Elasticsearch index
      updateEventInIndex(mediaPackageId, Opt.some(acl), dublinCore, Opt.some(startDateTime), Opt.some(endDateTime),
          Opt.some(userIds), Opt.some(captureAgentId), Opt.some(finalCaProperties), Opt.none());

      // Update last modified
      touchLastEntry(captureAgentId);
    } catch (SchedulerException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Failed to create event with id '{}':", mediaPackageId, e);
      throw new SchedulerException(e);
    }
  }

  @Override
  public Map<String, Period> addMultipleEvents(RRule rRule, Date start, Date end, Long duration, TimeZone tz,
          String captureAgentId, Set<String> userIds, MediaPackage templateMp, Map<String, String> wfProperties,
          Map<String, String> caMetadata, Opt<String> schedulingSource)
          throws UnauthorizedException, SchedulerConflictException, SchedulerException {
    // input Rrule is UTC. Needs to be adjusted to tz
    Util.adjustRrule(rRule, start, tz);
    List<Period> periods = Util.calculatePeriods(start, end, duration, rRule, tz);
    if (periods.isEmpty()) {
      return Collections.emptyMap();
    }
    return addMultipleEventInternal(periods, captureAgentId, userIds, templateMp, wfProperties, caMetadata,
            schedulingSource);
  }

  private Map<String, Period> addMultipleEventInternal(List<Period> periods, String captureAgentId,
          Set<String> userIds, MediaPackage templateMp, Map<String, String> wfProperties,
          Map<String, String> caMetadata, Opt<String> schedulingSource) throws SchedulerException {
    notNull(periods, "periods");
    requireTrue(periods.size() > 0, "periods");
    notEmpty(captureAgentId, "captureAgentId");
    notNull(userIds, "userIds");
    notNull(templateMp, "mediaPackages");
    notNull(wfProperties, "wfProperties");
    notNull(caMetadata, "caMetadata");
    notNull(schedulingSource, "schedulingSource");

    Map<String, Period> scheduledEvents = new ConcurrentHashMap<>();

    try {
      LinkedList<Id> ids = new LinkedList<>();
      AQueryBuilder qb = assetManager.createQuery();
      Predicate p = null;
      //While we don't have a list of IDs equal to the number of periods
      while (ids.size() <= periods.size()) {
        //Create a list of IDs equal to the number of periods, along with a set of AM predicates
        while (ids.size() <= periods.size()) {
          Id id = new IdImpl(UUID.randomUUID().toString());
          ids.add(id);
          Predicate np = qb.mediaPackageId(id.toString());
          //Haha, p = np jokes with the AM query language. Ha. Haha. Ha.  (Sob...)
          if (null == p) {
            p = np;
          } else {
            p = p.or(np);
          }
        }
        //Select the list of ids which alread exist.  Hint: this needs to be zero
        AResult result = qb.select(qb.nothing()).where(withOrganization(qb).and(p).and(qb.version().isLatest())).run();
        //If there is conflict, clear the list and start over
        if (result.getTotalSize() > 0) {
          ids.clear();
        }
      }

      Opt<String> seriesId = Opt.nul(StringUtils.trimToNull(templateMp.getSeries()));

      List<MediaPackage> conflictingEvents = findConflictingEvents(periods, captureAgentId, TimeZone.getDefault());
      if (conflictingEvents.size() > 0) {
        logger.info("Unable to add events, conflicting events found: {}", conflictingEvents);
        throw new SchedulerConflictException("Unable to add event, conflicting events found");
      }

      final Organization org = securityService.getOrganization();
      final User user = securityService.getUser();
      periods.parallelStream().forEach(event -> SecurityUtil.runAs(securityService, org, user, () -> {
        final int currentCounter = periods.indexOf(event);
        MediaPackage mediaPackage = (MediaPackage) templateMp.clone();
        Date startDate = new Date(event.getStart().getTime());
        Date endDate = new Date(event.getEnd().getTime());
        Id id = ids.get(currentCounter);

        //Get, or make, the DC catalog
        DublinCoreCatalog dc;
        Optional<DublinCoreCatalog> dcOpt = DublinCoreUtil.loadEpisodeDublinCore(workspace, templateMp);
        if (dcOpt.isPresent()) {
          dc = dcOpt.get();
          dc = (DublinCoreCatalog) dc.clone();
          // make sure to bind the OC_PROPERTY namespace
          dc.addBindings(XmlNamespaceContext
                  .mk(XmlNamespaceBinding.mk(DublinCores.OC_PROPERTY_NS_PREFIX, DublinCores.OC_PROPERTY_NS_URI)));
        } else {
          dc = DublinCores.mkOpencastEpisode().getCatalog();
        }

        // Set the new media package identifier
        mediaPackage.setIdentifier(id);

        // Update dublincore title and temporal
        String newTitle = dc.getFirst(DublinCore.PROPERTY_TITLE) + String.format(" %0" + Integer.toString(periods.size()).length() + "d", currentCounter + 1);
        dc.set(DublinCore.PROPERTY_TITLE, newTitle);
        DublinCoreValue eventTime = EncodingSchemeUtils.encodePeriod(new DCMIPeriod(startDate, endDate),
                Precision.Second);
        dc.set(DublinCore.PROPERTY_TEMPORAL, eventTime);
        try {
          mediaPackage = updateDublincCoreCatalog(mediaPackage, dc);
        } catch (Exception e) {
          Misc.chuck(e);
        }
        mediaPackage.setTitle(newTitle);

        String mediaPackageId = mediaPackage.getIdentifier().toString();
        //Converting from iCal4j DateTime objects to plain Date objects to prevent AMQ issues below
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setTime(event.getStart());
        Date startDateTime = cal.getTime();
        cal.setTime(event.getEnd());
        Date endDateTime = cal.getTime();
        // Load dublincore and acl for update
        Opt<DublinCoreCatalog> dublinCore = DublinCoreUtil.loadEpisodeDublinCore(workspace, mediaPackage)
            .map(Opt::some).orElse(Opt.none());
        AccessControlList acl = authorizationService.getActiveAcl(mediaPackage).getA();

        // Get updated agent properties
        Map<String, String> finalCaProperties = getFinalAgentProperties(caMetadata, wfProperties, captureAgentId,
                seriesId, dublinCore);

        // Persist asset
        String checksum = calculateChecksum(workspace, getEventCatalogUIAdapterFlavors(), startDateTime, endDateTime,
                captureAgentId, userIds, mediaPackage, dublinCore, wfProperties, finalCaProperties, acl);
        try {
          persistEvent(mediaPackageId, checksum, Opt.some(startDateTime), Opt.some(endDateTime),
                Opt.some(captureAgentId), Opt.some(userIds), Opt.some(mediaPackage), Opt.some(wfProperties),
                Opt.some(finalCaProperties), schedulingSource);
        } catch (Exception e) {
          Misc.chuck(e);
        }

        // Update live event
        updateLiveEvent(mediaPackageId, some(acl), dublinCore, Opt.some(startDateTime), Opt.some(endDateTime),
                Opt.some(captureAgentId), Opt.some(finalCaProperties));

        // Update Elasticsearch index
        updateEventInIndex(mediaPackageId, some(acl), dublinCore, Opt.some(startDateTime), Opt.some(endDateTime),
                Opt.some(userIds), Opt.some(captureAgentId), Opt.some(finalCaProperties), Opt.none());

        scheduledEvents.put(mediaPackageId, event);
        for (MediaPackageElement mediaPackageElement : mediaPackage.getElements()) {
          try {
            workspace.delete(mediaPackage.getIdentifier().toString(), mediaPackageElement.getIdentifier());
          } catch (NotFoundException | IOException e) {
            logger.warn("Failed to delete media package element", e);
          }
        }
      }));
      return scheduledEvents;
    } catch (SchedulerException e) {
      throw e;
    } catch (Exception e) {
      throw new SchedulerException(e);
    } finally {
      // Update last modified
      if (!scheduledEvents.isEmpty()) {
        touchLastEntry(captureAgentId);
      }
    }
  }

  @Override
  public void updateEvent(final String mpId, Opt<Date> startDateTime, Opt<Date> endDateTime, Opt<String> captureAgentId,
          Opt<Set<String>> userIds, Opt<MediaPackage> mediaPackage, Opt<Map<String, String>> wfProperties,
          Opt<Map<String, String>> caMetadata)
                  throws NotFoundException, UnauthorizedException, SchedulerException {
    updateEventInternal(mpId, startDateTime, endDateTime, captureAgentId, userIds, mediaPackage,
            wfProperties, caMetadata, false);
  }

  @Override
  public void updateEvent(final String mpId, Opt<Date> startDateTime, Opt<Date> endDateTime, Opt<String> captureAgentId,
          Opt<Set<String>> userIds, Opt<MediaPackage> mediaPackage, Opt<Map<String, String>> wfProperties,
          Opt<Map<String, String>> caMetadata, boolean allowConflict)
                throws NotFoundException, UnauthorizedException, SchedulerException {
    updateEventInternal(mpId, startDateTime, endDateTime, captureAgentId, userIds, mediaPackage,
            wfProperties, caMetadata, allowConflict);
  }

  private void updateEventInternal(final String mpId, Opt<Date> startDateTime,
          Opt<Date> endDateTime, Opt<String> captureAgentId, Opt<Set<String>> userIds,
          Opt<MediaPackage> mediaPackageOpt, Opt<Map<String, String>> wfProperties, Opt<Map<String, String>> caMetadata,
          boolean allowConflict) throws NotFoundException, SchedulerException {
    notEmpty(mpId, "mpId");
    notNull(startDateTime, "startDateTime");
    notNull(endDateTime, "endDateTime");
    notNull(captureAgentId, "captureAgentId");
    notNull(userIds, "userIds");
    notNull(mediaPackageOpt, "mediaPackageOpt");
    notNull(wfProperties, "wfProperties");
    notNull(caMetadata, "caMetadata");

    try {
      AQueryBuilder query = assetManager.createQuery();

      ASelectQuery select = query
              .select(query.snapshot())
              .where(withOrganization(query).and(query.mediaPackageId(mpId).and(query.version().isLatest())
                  .and(withOwner(query))));
      Opt<ARecord> optEvent = select.run().getRecords().head();
      Opt<ExtendedEventDto> optExtEvent = persistence.getEvent(mpId);
      if (optEvent.isNone() || optExtEvent.isNone())
        throw new NotFoundException("No event found while updating event " + mpId);

      ARecord record = optEvent.get();
      if (record.getSnapshot().isNone())
        throw new NotFoundException("No mediapackage found while updating event " + mpId);
      Snapshot snapshot = record.getSnapshot().get();
      MediaPackage archivedMediaPackage = snapshot.getMediaPackage();

      Opt<DublinCoreCatalog> archivedDublinCoreOpt = loadEpisodeDublinCoreFromAsset(snapshot);
      if (archivedDublinCoreOpt.isNone())
        throw new NotFoundException("No dublincore found while updating event " + mpId);
      DublinCoreCatalog archivedDublinCore = archivedDublinCoreOpt.get();
      AccessControlList archivedAcl = authorizationService.getActiveAcl(archivedMediaPackage).getA();

      final ExtendedEventDto extendedEventDto = optExtEvent.get();
      Date start = extendedEventDto.getStartDate();
      Date end = extendedEventDto.getEndDate();

      if ((startDateTime.isSome() || endDateTime.isSome()) && endDateTime.getOr(end).before(startDateTime.getOr(start)))
        throw new SchedulerException("The end date is before the start date");

      String agentId = extendedEventDto.getCaptureAgentId();
      Opt<String> seriesId = Opt.nul(archivedMediaPackage.getSeries());

      // Check for conflicting events
      // Check scheduling conflicts in case a property relevant for conflicts has changed
      if ((captureAgentId.isSome() || startDateTime.isSome() || endDateTime.isSome())
            && (!allowConflict || !isAdmin())) {
        List<MediaPackage> conflictingEvents = $(findConflictingEvents(captureAgentId.getOr(agentId),
                startDateTime.getOr(start), endDateTime.getOr(end))).filter(new Fn<MediaPackage, Boolean>() {
                    @Override
                    public Boolean apply(MediaPackage mp) {
                    return !mpId.equals(mp.getIdentifier().toString());
                  }
                  }).toList();
        if (conflictingEvents.size() > 0) {
          logger.info("Unable to update event {}, conflicting events found: {}", mpId, conflictingEvents);
          throw new SchedulerConflictException("Unable to update event, conflicting events found for event " + mpId);
        }
      }

      Set<String> presenters = getPresenters(Opt.nul(extendedEventDto.getPresenters()).getOr(""));
      Map<String, String> wfProps = deserializeExtendedEventProperties(extendedEventDto.getWorkflowProperties());
      Map<String, String> caProperties = deserializeExtendedEventProperties(
              extendedEventDto.getCaptureAgentProperties());

      boolean propertiesChanged = false;
      boolean dublinCoreChanged = false;

      // Get workflow properties
      for (Map<String, String> wfPropsToUpdate : wfProperties) {
        propertiesChanged = true;
        wfProps = wfPropsToUpdate;
      }

      // Get capture agent properties
      for (Map<String, String> caMetadataToUpdate : caMetadata) {
        propertiesChanged = true;
        caProperties = caMetadataToUpdate;
      }

      if (captureAgentId.isSome())
        propertiesChanged = true;

      Opt<AccessControlList> changedAclOpt = Opt.none();
      Opt<DublinCoreCatalog> changedDublinCoreOpt = Opt.none();
      if (mediaPackageOpt.isSome()) {
        MediaPackage mediaPackage = mediaPackageOpt.get();
        // Check for series change
        if (ne(archivedMediaPackage.getSeries(), mediaPackage.getSeries())) {
          propertiesChanged = true;
          seriesId = Opt.nul(mediaPackage.getSeries());
        }

        // Check for ACL change
        AccessControlList acl = authorizationService.getActiveAcl(mediaPackage).getA();
        if (!AccessControlUtil.equals(acl, archivedAcl)) {
          changedAclOpt = some(acl);
        }

        // Check for dublin core change
        Optional<DublinCoreCatalog> dublinCoreOpt = DublinCoreUtil.loadEpisodeDublinCore(workspace,
                mediaPackage);
        if (dublinCoreOpt.isPresent() && !DublinCoreUtil.equals(archivedDublinCore, dublinCoreOpt.get())) {
          dublinCoreChanged = true;
          propertiesChanged = true;
          changedDublinCoreOpt = dublinCoreOpt.map(Opt::some).orElse(Opt.none());
        }
      }

      //update metadata for dublincore
      DublinCoreCatalog dublinCore = changedDublinCoreOpt.getOr(archivedDublinCore);
      DublinCoreCatalog dublinCoreCopy = (DublinCoreCatalog) dublinCore.clone();
      if (startDateTime.isSome() && endDateTime.isSome()) {
        DublinCoreValue eventTime = EncodingSchemeUtils.encodePeriod(
                new DCMIPeriod(startDateTime.get(), endDateTime.get()), Precision.Second);
        dublinCore.set(DublinCore.PROPERTY_TEMPORAL, eventTime);
      }
      if (captureAgentId.isSome()) {
        dublinCore.set(DublinCore.PROPERTY_SPATIAL, captureAgentId.get());
      }
      if (!DublinCoreUtil.equals(dublinCore, dublinCoreCopy)) {
        dublinCoreChanged = true;
        changedDublinCoreOpt = Opt.some(dublinCore);
        mediaPackageOpt = Opt.some(updateDublincCoreCatalog(mediaPackageOpt.getOr(archivedMediaPackage),
                changedDublinCoreOpt.get()));
      }

      Opt<Map<String, String>> finalCaProperties = Opt.none();
      if (propertiesChanged) {
        finalCaProperties = Opt.some(getFinalAgentProperties(caProperties, wfProps, captureAgentId.getOr(agentId),
                                                             seriesId, some(changedDublinCoreOpt.getOr(
                                                                     archivedDublinCore))));
      }

      String checksum = calculateChecksum(workspace, getEventCatalogUIAdapterFlavors(), startDateTime.getOr(start),
              endDateTime.getOr(end), captureAgentId.getOr(agentId), userIds.getOr(presenters),
              mediaPackageOpt.getOr(archivedMediaPackage),
              some(changedDublinCoreOpt.getOr(archivedDublinCore)), wfProperties.getOr(wfProps),
              finalCaProperties.getOr(caProperties), changedAclOpt.getOr(new AccessControlList()));

      String oldChecksum = extendedEventDto.getChecksum();
      if (checksum.equals(oldChecksum)) {
        logger.debug("Updated event {} has same checksum, ignore update", mpId);
        return;
      }

      // Update asset
      persistEvent(mpId, checksum, startDateTime, endDateTime, captureAgentId, userIds,
              mediaPackageOpt, wfProperties, finalCaProperties, Opt.none());

      // Update live event
      updateLiveEvent(mpId, changedAclOpt, changedDublinCoreOpt, startDateTime, endDateTime, Opt.some(agentId),
              finalCaProperties);

      // Update Elasticsearch index
      updateEventInIndex(mpId, changedAclOpt, changedDublinCoreOpt, startDateTime, endDateTime, userIds,
              Opt.some(agentId), finalCaProperties, Opt.none());

      // Update last modified
      if (propertiesChanged || dublinCoreChanged || startDateTime.isSome() || endDateTime.isSome()) {
        touchLastEntry(agentId);
        for (String agent : captureAgentId) {
          touchLastEntry(agent);
        }
      }
    } catch (NotFoundException | SchedulerException e) {
      throw e;
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  private boolean isAdmin() {
    return (securityService.getUser().hasRole(GLOBAL_ADMIN_ROLE)
            || securityService.getUser().hasRole(securityService.getOrganization().getAdminRole()));
  }

  private Opt<DublinCoreCatalog> loadEpisodeDublinCoreFromAsset(Snapshot snapshot) {
    Option<MediaPackageElement> dcCatalog = mlist(snapshot.getMediaPackage().getElements())
            .filter(MediaPackageSupport.Filters.isEpisodeDublinCore).headOpt();
    if (dcCatalog.isNone())
      return Opt.none();

    Opt<Asset> asset = assetManager.getAsset(snapshot.getVersion(),
            snapshot.getMediaPackage().getIdentifier().toString(), dcCatalog.get().getIdentifier());
    if (asset.isNone())
      return Opt.none();

    if (Availability.OFFLINE.equals(asset.get().getAvailability()))
      return Opt.none();

    InputStream inputStream = null;
    try {
      inputStream = asset.get().getInputStream();
      return Opt.some(DublinCores.read(inputStream));
    } finally {
      IOUtils.closeQuietly(inputStream);
    }
  }

  @Override
  public synchronized void removeEvent(String mediaPackageId)
          throws NotFoundException, SchedulerException {
    notEmpty(mediaPackageId, "mediaPackageId");

    boolean notFoundInDatabase = false;
    boolean notFoundInAssetManager;
    try {
      // Remove from database
      try {
        Opt<ExtendedEventDto> extEvtOpt = persistence.getEvent(mediaPackageId);
        if (extEvtOpt.isSome()) {
          String agentId = extEvtOpt.get().getCaptureAgentId();
          persistence.deleteEvent(mediaPackageId);
          if (StringUtils.isNotEmpty(agentId)) {
            touchLastEntry(agentId);
          }
        } else {
          notFoundInDatabase = true;
        }
      } catch (NotFoundException e) {
        notFoundInDatabase = true;
      }

      // Delete scheduler snapshot
      AQueryBuilder query = assetManager.createQuery();
      long deletedSnapshots = query.delete(SNAPSHOT_OWNER, query.snapshot())
              .where(withOrganization(query).and(query.mediaPackageId(mediaPackageId)))
              .name("delete episode").run();
      notFoundInAssetManager = deletedSnapshots == 0;

      // Update live event
      sendSchedulerUpdate(new SchedulerItemList(mediaPackageId, SchedulerItem.delete()));

      // Update Elasticsearch index
      removeSchedulingInfoFromIndex(mediaPackageId);
    } catch (Exception e) {
      logger.error("Could not remove event '{}' from persistent storage: {}", mediaPackageId, e);
      throw new SchedulerException(e);
    }

    if (notFoundInDatabase && notFoundInAssetManager) {
      throw new NotFoundException();
    }
  }

  @Override
  public MediaPackage getMediaPackage(String mediaPackageId) throws NotFoundException, SchedulerException {
    notEmpty(mediaPackageId, "mediaPackageId");

    try {
      return getEventMediaPackage(mediaPackageId);
    } catch (RuntimeNotFoundException e) {
      throw e.getWrappedException();
    } catch (Exception e) {
      logger.error("Failed to get mediapackage of event '{}':", mediaPackageId, e);
      throw new SchedulerException(e);
    }
  }

  @Override
  public DublinCoreCatalog getDublinCore(String mediaPackageId) throws NotFoundException, SchedulerException {
    notEmpty(mediaPackageId, "mediaPackageId");

    try {
      AQueryBuilder query = assetManager.createQuery();
      AResult result = query.select(query.snapshot())
              .where(withOrganization(query).and(query.mediaPackageId(mediaPackageId)).and(withOwner(query))
              .and(query.version().isLatest()))
              .run();
      Opt<ARecord> record = result.getRecords().head();
      if (record.isNone())
        throw new NotFoundException();

      Opt<DublinCoreCatalog> dublinCore = loadEpisodeDublinCoreFromAsset(record.get().getSnapshot().get());
      if (dublinCore.isNone())
        throw new NotFoundException("No dublincore catalog found " + mediaPackageId);

      return dublinCore.get();
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Failed to get dublin core catalog of event '{}':", mediaPackageId, e);
      throw new SchedulerException(e);
    }
  }

  @Override
  public TechnicalMetadata getTechnicalMetadata(String mediaPackageId)
          throws NotFoundException, UnauthorizedException, SchedulerException {
    notEmpty(mediaPackageId, "mediaPackageId");

    try {
      final Opt<ExtendedEventDto> extEvt = persistence.getEvent(mediaPackageId);
      if (extEvt.isNone())
        throw new NotFoundException();

      return getTechnicalMetadata(extEvt.get());
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Failed to get technical metadata of event '{}':", mediaPackageId, e);
      throw new SchedulerException(e);
    }
  }

  @Override
  public Map<String, String> getWorkflowConfig(String mediaPackageId) throws NotFoundException, SchedulerException {
    notEmpty(mediaPackageId, "mediaPackageId");

    try {
      Opt<ExtendedEventDto> record = persistence.getEvent(mediaPackageId);
      if (record.isNone())
        throw new NotFoundException();
      return deserializeExtendedEventProperties(record.get().getWorkflowProperties());
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Failed to get workflow configuration of event '{}':", mediaPackageId, e);
      throw new SchedulerException(e);
    }
  }

  @Override
  public Map<String, String> getCaptureAgentConfiguration(String mediaPackageId)
          throws NotFoundException, SchedulerException {
    notEmpty(mediaPackageId, "mediaPackageId");

    try {
      Opt<ExtendedEventDto> record = persistence.getEvent(mediaPackageId);
      if (record.isNone())
        throw new NotFoundException();
      return deserializeExtendedEventProperties(record.get().getCaptureAgentProperties());
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Failed to get capture agent contiguration of event '{}':", mediaPackageId, e);
      throw new SchedulerException(e);
    }
  }

  @Override
  public int getEventCount() throws SchedulerException {
    try {
      return persistence.countEvents();
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public List<MediaPackage> search(Opt<String> captureAgentId, Opt<Date> startsFrom, Opt<Date> startsTo,
          Opt<Date> endFrom, Opt<Date> endTo) throws SchedulerException {
    try {
      return persistence.search(captureAgentId, startsFrom, startsTo, endFrom, endTo, Opt.none()).stream()
          .map(ExtendedEventDto::getMediaPackageId)
          .map(this::getEventMediaPackage).collect(Collectors.toList());
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public Opt<MediaPackage> getCurrentRecording(String captureAgentId) throws SchedulerException {
    try {
      final Date now = new Date();
      List<ExtendedEventDto> result = persistence.search(Opt.some(captureAgentId), Opt.none(), Opt.some(now), Opt.some(now), Opt.none(), Opt.some(1));
      if (result.isEmpty()) {
        return Opt.none();
      }
      return Opt.some(getEventMediaPackage(result.get(0).getMediaPackageId()));
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public Opt<MediaPackage> getUpcomingRecording(String captureAgentId) throws SchedulerException {
    try {
      final Date now = new Date();
      List<ExtendedEventDto> result = persistence.search(Opt.some(captureAgentId), Opt.some(now), Opt.none(), Opt.none(), Opt.none(), Opt.some(1));
      if (result.isEmpty()) {
        return Opt.none();
      }
      return Opt.some(getEventMediaPackage(result.get(0).getMediaPackageId()));
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public List<MediaPackage> findConflictingEvents(String captureDeviceID, Date startDate, Date endDate)
      throws SchedulerException {
    try {
      final Organization organization = securityService.getOrganization();
      final User user = SecurityUtil.createSystemUser(systemUserName, organization);
      List<MediaPackage> conflictingEvents = new ArrayList<>();

      SecurityUtil.runAs(securityService, organization, user, () -> {
        try {
          persistence.getEvents(captureDeviceID, startDate, endDate, Util.EVENT_MINIMUM_SEPARATION_MILLISECONDS)
                  .stream()
                  .map(id -> getEventMediaPackage(id, false))
                  .forEach(conflictingEvents::add);
        } catch (SchedulerServiceDatabaseException e) {
          logger.error("Failed to get conflicting events", e);
        }
      });

      return conflictingEvents;

    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public List<MediaPackage> findConflictingEvents(String captureAgentId, RRule rrule, Date start, Date end,
          long duration, TimeZone tz) throws SchedulerException {
    notEmpty(captureAgentId, "captureAgentId");
    notNull(rrule, "rrule");
    notNull(start, "start");
    notNull(end, "end");
    notNull(tz, "timeZone");

    Util.adjustRrule(rrule, start, tz);
    final List<Period> periods =  Util.calculatePeriods(start, end, duration, rrule, tz);

    if (periods.isEmpty()) {
      return Collections.emptyList();
    }

    return findConflictingEvents(periods, captureAgentId, tz);
  }

  private boolean checkPeriodOverlap(final List<Period> periods) {
    final List<Period> sortedPeriods = new ArrayList<>(periods);
    sortedPeriods.sort(Comparator.comparing(Period::getStart));
    Period prior = periods.get(0);
    for (Period current : periods.subList(1, periods.size())) {
      if (current.getStart().compareTo(prior.getEnd()) < 0) {
        return true;
      }
      prior = current;
    }
    return false;
  }

  private List<MediaPackage> findConflictingEvents(List<Period> periods, String captureAgentId, TimeZone tz)
          throws SchedulerException {
    notEmpty(captureAgentId, "captureAgentId");
    notNull(periods, "periods");
    requireTrue(periods.size() > 0, "periods");

    // First, check if there are overlaps inside the periods to be added (this is possible if you specify an RRULE via
    // the external API, for example; the admin ui should prevent this from happening). Then check for conflicts with
    // existing events.
    if (checkPeriodOverlap(periods)) {
      throw new IllegalArgumentException("RRULE periods overlap");
    }

    try {
      TimeZoneRegistry registry = TimeZoneRegistryFactory.getInstance().createRegistry();

      Set<MediaPackage> events = new HashSet<>();

      for (Period event : periods) {
        event.setTimeZone(registry.getTimeZone(tz.getID()));
        final Date startDate = event.getStart();
        final Date endDate = event.getEnd();

        events.addAll(findConflictingEvents(captureAgentId, startDate, endDate));
      }

      return new ArrayList<>(events);
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public String getCalendar(Opt<String> captureAgentId, Opt<String> seriesId, Opt<Date> cutoff)
          throws SchedulerException {

    try {
      final Map<String, ExtendedEventDto> searchResult = persistence.search(captureAgentId, Opt.none(), cutoff,
          Opt.some(DateTime.now().minusHours(1).toDate()), Opt.none(), Opt.none()).stream()
          .collect(Collectors.toMap(ExtendedEventDto::getMediaPackageId, Function.identity()));
      final AQueryBuilder query = assetManager.createQuery();
      final AResult result = query.select(query.snapshot())
          .where(withOrganization(query).and(query.mediaPackageIds(searchResult.keySet().toArray(new String[0])))
              .and(withOwner(query)).and(query.version().isLatest()))
          .run();

      final CalendarGenerator cal = new CalendarGenerator(seriesService);
      for (final ARecord record : result.getRecords()) {
        final Opt<MediaPackage> optMp = record.getSnapshot().map(episodeToMp);

        // If the event media package is empty, skip the event
        if (optMp.isNone()) {
          logger.warn("Mediapackage for event '{}' can't be found, event is not recorded", record.getMediaPackageId());
          continue;
        }

        if (seriesId.isSome() && !seriesId.get().equals(optMp.get().getSeries())) {
          continue;
        }

        Opt<DublinCoreCatalog> catalogOpt = loadEpisodeDublinCoreFromAsset(record.getSnapshot().get());
        if (catalogOpt.isNone()) {
          logger.warn("No episode catalog available, skipping!");
          continue;
        }

        final Map<String, String> caMetadata = deserializeExtendedEventProperties(searchResult.get(record.getMediaPackageId()).getCaptureAgentProperties());

        // If the even properties are empty, skip the event
        if (caMetadata.isEmpty()) {
          logger.warn("Properties for event '{}' can't be found, event is not recorded", record.getMediaPackageId());
          continue;
        }

        final String agentId = searchResult.get(record.getMediaPackageId()).getCaptureAgentId();
        final Date start = searchResult.get(record.getMediaPackageId()).getStartDate();
        final Date end = searchResult.get(record.getMediaPackageId()).getEndDate();
        final Date lastModified = record.getSnapshot().get().getArchivalDate();

        // Add the entry to the calendar, skip it with a warning if adding fails
        try {
          cal.addEvent(optMp.get(), catalogOpt.get(), agentId, start, end, lastModified, toPropertyString(caMetadata));
        } catch (Exception e) {
          logger.warn("Error adding event '{}' to calendar, event is not recorded", record.getMediaPackageId(), e);
        }
      }

      // Only validate calendars with events. Without any events, the iCalendar won't validate
      if (cal.getCalendar().getComponents().size() > 0) {
        cal.getCalendar().validate();
      }

      return cal.getCalendar().toString();

    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public String getScheduleLastModified(String captureAgentId) throws SchedulerException {
    notEmpty(captureAgentId, "captureAgentId");

    try {
      String lastModified = lastModifiedCache.getIfPresent(captureAgentId);
      if (lastModified != null)
        return lastModified;

      populateLastModifiedCache();

      lastModified = lastModifiedCache.getIfPresent(captureAgentId);

      // If still null set the empty calendar ETag
      if (lastModified == null) {
        lastModified = EMPTY_CALENDAR_ETAG;
        lastModifiedCache.put(captureAgentId, lastModified);
      }
      return lastModified;
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public void removeScheduledRecordingsBeforeBuffer(long buffer) throws SchedulerException {
    DateTime end = new DateTime(DateTimeZone.UTC).minus(buffer * 1000);

    logger.info("Starting to look for scheduled recordings that have finished before {}.",
            DateTimeSupport.toUTC(end.getMillis()));

    List<ExtendedEventDto> finishedEvents;
    try {
      finishedEvents = persistence.search(Opt.<String> none(), Opt.<Date> none(), Opt.<Date> none(), Opt.<Date> none(),
              Opt.some(end.toDate()), Opt.none());
      logger.debug("Found {} events from search.", finishedEvents.size());
    } catch (Exception e) {
      throw new SchedulerException(e);
    }

    int recordingsRemoved = 0;
    for (ExtendedEventDto extEvt : finishedEvents) {
      final String eventId = extEvt.getMediaPackageId();
      try {
        removeEvent(eventId);
        logger.debug("Sucessfully removed scheduled event with id " + eventId);
        recordingsRemoved++;
      } catch (NotFoundException e) {
        logger.debug("Skipping event with id {} because it is not found", eventId);
      } catch (Exception e) {
        logger.warn("Unable to delete event with id '{}':", eventId, e);
      }
    }

    logger.info("Found {} to remove that ended before {}.", recordingsRemoved, DateTimeSupport.toUTC(end.getMillis()));
  }

  @Override
  public boolean updateRecordingState(String id, String state) throws NotFoundException, SchedulerException {
    notEmpty(id, "id");
    notEmpty(state, "state");

    if (!RecordingState.KNOWN_STATES.contains(state)) {
      logger.warn("Invalid recording state: {}.", state);
      return false;
    }

    try {
      final Opt<ExtendedEventDto> optExtEvt = persistence.getEvent(id);

      if (optExtEvt.isNone())
        throw new NotFoundException();

      final String prevRecordingState = optExtEvt.get().getRecordingState();
      final Recording r = new RecordingImpl(id, state);
      if (!state.equals(prevRecordingState)) {
        logger.debug("Setting Recording {} to state {}.", id, state);

        // Update live event
        sendSchedulerUpdate(new SchedulerItemList(r.getID(), Collections.singletonList(SchedulerItem
                .updateRecordingStatus(r.getState(), r.getLastCheckinTime()))));

        // Update Elasticsearch index
        updateEventInIndex(r.getID(), Opt.none(), Opt.none(), Opt.none(), Opt.none(), Opt.none(),
                Opt.none(), Opt.none(), Opt.some(r.getState()));
      } else {
        logger.debug("Recording state not changed");
      }

      persistence.storeEvent(
          id,
          securityService.getOrganization().getId(),
          Opt.none(),
          Opt.none(),
          Opt.none(),
          Opt.none(),
          Opt.some(r.getState()),
          Opt.some(r.getLastCheckinTime()),
          Opt.none(),
          Opt.none(),
          Opt.none(),
          Opt.none(),
          Opt.none()
      );
      return true;
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public Recording getRecordingState(String id) throws NotFoundException, SchedulerException {

    notEmpty(id, "id");

    try {
      Opt<ExtendedEventDto> extEvt = persistence.getEvent(id);

      if (extEvt.isNone() || extEvt.get().getRecordingState() == null) {
        throw new NotFoundException();
      }

      return new RecordingImpl(id, extEvt.get().getRecordingState(), extEvt.get().getRecordingLastHeard());
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public void removeRecording(String id) throws NotFoundException, SchedulerException {
    notEmpty(id, "id");

    try {
      persistence.resetRecordingState(id);

      // Update live event
      sendSchedulerUpdate(new SchedulerItemList(id, SchedulerItem.deleteRecordingState()));

      // Update Elasticsearch index
      removeRecordingStatusFromIndex(id);
    } catch (NotFoundException e) {
      throw e;
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  @Override
  public Map<String, Recording> getKnownRecordings() throws SchedulerException {
    try {
      return persistence.getKnownRecordings().parallelStream()
          .collect(
              Collectors.toMap(ExtendedEventDto::getMediaPackageId,
              dto -> new RecordingImpl(dto.getMediaPackageId(), dto.getRecordingState(), dto.getRecordingLastHeard()))
          );
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  private synchronized void persistEvent(final String mpId, final String checksum,
          final Opt<Date> startDateTime, final Opt<Date> endDateTime, final Opt<String> captureAgentId,
          final Opt<Set<String>> userIds, final Opt<MediaPackage> mediaPackage,
          final Opt<Map<String, String>> wfProperties, final Opt<Map<String, String>> caProperties,
          final Opt<String> schedulingSource) throws SchedulerServiceDatabaseException {
    // Store scheduled mediapackage
    for (MediaPackage mpToUpdate : mediaPackage) {
      assetManager.takeSnapshot(SNAPSHOT_OWNER, mpToUpdate);
    }

    // Store extended event
    persistence.storeEvent(
        mpId,
        securityService.getOrganization().getId(),
        captureAgentId,
        startDateTime,
        endDateTime,
        schedulingSource,
        Opt.none(),
        Opt.none(),
        userIds.isSome() ? Opt.some(String.join(",", userIds.get())) : Opt.none(),
        Opt.some(new Date()),
        Opt.some(checksum),
        wfProperties,
        caProperties
    );
  }

  /**
   * Update the event in the Elasticsearch index. Fields will only be updated of the corresponding Opt is not none.
   *
   * @param mediaPackageId
   * @param index
   * @param acl
   * @param dublinCore
   * @param startTime
   * @param endTime
   * @param presenters
   * @param agentId
   * @param properties
   * @param recordingStatus
   */
  private void updateEventInIndex(String mediaPackageId, Opt<AccessControlList> acl,
          Opt<DublinCoreCatalog> dublinCore, Opt<Date> startTime, Opt<Date> endTime, Opt<Set<String>> presenters,
          Opt<String> agentId, Opt<Map<String, String>> properties, Opt<String> recordingStatus) {

    String organization = getSecurityService().getOrganization().getId();
    User user = getSecurityService().getUser();

    Function<Optional<Event>, Optional<Event>> updateFunction = (Optional<Event> eventOpt) -> {
      Event event = eventOpt.orElse(new Event(mediaPackageId, organization));

      if (acl.isSome()) {
        event.setAccessPolicy(AccessControlParser.toJsonSilent(acl.get()));
      }
      if (dublinCore.isSome()) {
        EventIndexUtils.updateEvent(event, dublinCore.get());
        if (isBlank(event.getCreator()))
          event.setCreator(getSecurityService().getUser().getName());

        // Update series name if not already done
        try {
          EventIndexUtils.updateSeriesName(event, organization, user, index);
        } catch (SearchIndexException e) {
          logger.error("Error updating the series name of the event {} in the {} index.", mediaPackageId,
                  index.getIndexName(), e);
        }
      }
      if (presenters.isSome()) {
        event.setTechnicalPresenters(new ArrayList<>(presenters.get()));
      }
      if (agentId.isSome()) {
        event.setAgentId(agentId.get());
      }
      if (recordingStatus.isSome() && !recordingStatus.get().equals(RecordingState.UNKNOWN)) {
        event.setRecordingStatus(recordingStatus.get());
      }
      if (properties.isSome()) {
        event.setAgentConfiguration(properties.get());
      }
      if (startTime.isSome()) {
        String startTimeStr = startTime == null ? null : DateTimeSupport.toUTC(startTime.get().getTime());
        event.setTechnicalStartTime(startTimeStr);
      }
      if (endTime.isSome()) {
        String endTimeStr = endTime == null ? null : DateTimeSupport.toUTC(endTime.get().getTime());
        event.setTechnicalEndTime(endTimeStr);
      }

      return Optional.of(event);
    };

    try {
      index.addOrUpdateEvent(mediaPackageId, updateFunction, organization, user);
      logger.debug("Scheduled event {} updated in the {} index.", mediaPackageId, index.getIndexName());
    } catch (SearchIndexException e) {
      logger.error("Error updating the scheduled event {} in the {} index.", mediaPackageId, index.getIndexName(), e);
    }
  }

  /**
   * Set recording status to null for this event in the Elasticsearch index.
   *
   * @param mediaPackageId
   * @param index
   */
  private void removeRecordingStatusFromIndex(String mediaPackageId) {
    String organization = getSecurityService().getOrganization().getId();
    User user = getSecurityService().getUser();

    Function<Optional<Event>, Optional<Event>> updateFunction = (Optional<Event> eventOpt) -> {
      Event event = eventOpt.orElse(new Event(mediaPackageId, organization));
      event.setRecordingStatus(null);
      return Optional.of(event);
    };

    try {
      index.addOrUpdateEvent(mediaPackageId, updateFunction, organization, user);
      logger.debug("Recording state of event {} removed from the {} index.", mediaPackageId, index.getIndexName());
    } catch (SearchIndexException e) {
      logger.error("Failed to remove the recording state of event {} from the {} index.", mediaPackageId,
              index.getIndexName(), e);
    }
  }

  /**
   * Remove scheduling information for this event from the Elasticsearch index.
   *
   * @param mediaPackageId
   * @param index
   */
  private void removeSchedulingInfoFromIndex(String mediaPackageId) {
    String orgId = getSecurityService().getOrganization().getId();

    try {
      index.deleteEvent(mediaPackageId, orgId);
      logger.debug("Scheduling information of event {} removed from the {} index.", mediaPackageId,
              index.getIndexName());
    } catch (SearchIndexException e) {
      logger.error("Failed to delete the scheduling information of event {} from the {} index.", mediaPackageId,
              index.getIndexName(), e);
    }
  }

  /**
   * Send messages to trigger an update in the LiveScheduleService.
   *
   * @param mpId
   * @param acl
   * @param dublinCore
   * @param startTime
   * @param endTime
   * @param agentId
   * @param properties
   */
  private void updateLiveEvent(String mpId, Opt<AccessControlList> acl, Opt<DublinCoreCatalog> dublinCore,
          Opt<Date> startTime, Opt<Date> endTime, Opt<String> agentId, Opt<Map<String, String>> properties) {
    List<SchedulerItem> items = new ArrayList<>();
    if (acl.isSome()) {
      items.add(SchedulerItem.updateAcl(acl.get()));
    }
    if (dublinCore.isSome()) {
      items.add(SchedulerItem.updateCatalog(dublinCore.get()));
    }
    if (startTime.isSome()) {
      items.add(SchedulerItem.updateStart(startTime.get()));
    }
    if (endTime.isSome()) {
      items.add(SchedulerItem.updateEnd(endTime.get()));
    }
    if (agentId.isSome()) {
      items.add(SchedulerItem.updateAgent(agentId.get()));
    }
    if (properties.isSome()) {
      items.add(SchedulerItem.updateProperties(properties.get()));
    }

    if (!items.isEmpty()) {
      sendSchedulerUpdate(new SchedulerItemList(mpId, items));
    }
  }

  private Map<String, String> getFinalAgentProperties(Map<String, String> caMetadata, Map<String, String> wfProperties,
          String captureAgentId, Opt<String> seriesId, Opt<DublinCoreCatalog> dublinCore) {
    Map<String, String> properties = new HashMap<>();
    for (Entry<String, String> entry : caMetadata.entrySet()) {
      if (entry.getKey().startsWith(WORKFLOW_CONFIG_PREFIX))
        continue;
      properties.put(entry.getKey(), entry.getValue());
    }
    for (Entry<String, String> entry : wfProperties.entrySet()) {
      properties.put(WORKFLOW_CONFIG_PREFIX.concat(entry.getKey()), entry.getValue());
    }
    if (dublinCore.isSome()) {
      properties.put("event.title", dublinCore.get().getFirst(DublinCore.PROPERTY_TITLE));
    }
    if (seriesId.isSome()) {
      properties.put("event.series", seriesId.get());
    }
    properties.put("event.location", captureAgentId);
    return properties;
  }

  private void touchLastEntry(String captureAgentId) throws SchedulerException {
    // touch last entry
    try {
      logger.debug("Marking calendar feed for {} as modified", captureAgentId);
      persistence.touchLastEntry(captureAgentId);
      populateLastModifiedCache();
    } catch (SchedulerServiceDatabaseException e) {
      logger.error("Failed to update last modified entry of agent '{}':", captureAgentId, e);
    }
  }

  private void populateLastModifiedCache() throws SchedulerException {
    try {
      Map<String, Date> lastModifiedDates = persistence.getLastModifiedDates();
      for (Entry<String, Date> entry : lastModifiedDates.entrySet()) {
        Date lastModifiedDate = entry.getValue() != null ? entry.getValue() : new Date();
        lastModifiedCache.put(entry.getKey(), generateLastModifiedHash(lastModifiedDate));
      }
    } catch (Exception e) {
      throw new SchedulerException(e);
    }
  }

  private String generateLastModifiedHash(Date lastModifiedDate) {
    return "mod" + Long.toString(lastModifiedDate.getTime());
  }

  private String toPropertyString(Map<String, String> properties) {
    StringBuilder wfPropertiesString = new StringBuilder();
    for (Map.Entry<String, String> entry : properties.entrySet())
      wfPropertiesString.append(entry.getKey() + "=" + entry.getValue() + "\n");
    return wfPropertiesString.toString();
  }

  private MediaPackage getEventMediaPackage(final String mediaPackageId, boolean checkOwner) {
    AQueryBuilder query = assetManager.createQuery();
    var predicate = withOrganization(query)
            .and(query.mediaPackageId(mediaPackageId))
            .and(query.version().isLatest());
    if (checkOwner) {
      predicate = predicate.and(withOwner(query));
    }

    Opt<ARecord> record = query.select(query.snapshot()).where(predicate).run().getRecords().head();
    if (record.isNone())
      throw new RuntimeNotFoundException(new NotFoundException());

    return record.bind(recordToMp).get();
  }

  private MediaPackage getEventMediaPackage(final String mediaPackageId) {
    return getEventMediaPackage(mediaPackageId, true);
  }

  /**
   *
   * @param mp
   *          the mediapackage to update
   * @param dc
   *          the dublincore metadata to use to update the mediapackage
   * @return the updated mediapackage
   * @throws IOException
   *           Thrown if an IO error occurred adding the dc catalog file
   * @throws MediaPackageException
   *           Thrown if an error occurred updating the mediapackage or the mediapackage does not contain a catalog
   */
  private MediaPackage updateDublincCoreCatalog(MediaPackage mp, DublinCoreCatalog dc)
          throws IOException, MediaPackageException {
    try (InputStream inputStream = IOUtils.toInputStream(dc.toXmlString(), "UTF-8")) {
      // Update dublincore catalog
      Catalog[] catalogs = mp.getCatalogs(MediaPackageElements.EPISODE);
      if (catalogs.length > 0) {
        Catalog catalog = catalogs[0];
        URI uri = workspace.put(mp.getIdentifier().toString(), catalog.getIdentifier(), "dublincore.xml", inputStream);
        catalog.setURI(uri);
        // setting the URI to a new source so the checksum will most like be invalid
        catalog.setChecksum(null);
      } else {
        throw new MediaPackageException("Unable to find catalog");
      }
    }
    return mp;
  }

  private TechnicalMetadata getTechnicalMetadata(ExtendedEventDto extEvt) {
    final String agentId = extEvt.getCaptureAgentId();
    final Date start = extEvt.getStartDate();
    final Date end = extEvt.getEndDate();
    final Set<String> presenters = getPresenters(Opt.nul(extEvt.getPresenters()).getOr(""));
    final Opt<String> recordingStatus = Opt.nul(extEvt.getRecordingState());
    final Opt<Long> lastHeard = Opt.nul(extEvt.getRecordingLastHeard());
    final Map<String, String> caMetadata = deserializeExtendedEventProperties(extEvt.getCaptureAgentProperties());
    final Map<String, String> wfProperties = deserializeExtendedEventProperties(extEvt.getWorkflowProperties());

    Recording recording = null;
    if (recordingStatus.isSome() && lastHeard.isSome())
      recording = new RecordingImpl(extEvt.getMediaPackageId(), recordingStatus.get(), lastHeard.get());

    return new TechnicalMetadataImpl(extEvt.getMediaPackageId(), agentId, start, end, presenters, wfProperties,
            caMetadata, Opt.nul(recording));
  }

  private Predicate withOrganization(AQueryBuilder query) {
    return query.organizationId().eq(securityService.getOrganization().getId());
  }

  private Predicate withOwner(AQueryBuilder query) {
    return query.owner().eq(SNAPSHOT_OWNER);
  }

  private Set<String> getPresenters(String presentersString) {
    return new HashSet<>(Arrays.asList(StringUtils.split(presentersString, ",")));
  }

  /**
   * @return A {@link List} of {@link MediaPackageElementFlavor} that provide the extended metadata to the front end.
   */
  private List<MediaPackageElementFlavor> getEventCatalogUIAdapterFlavors() {
    String organization = securityService.getOrganization().getId();
    return Stream.$(eventCatalogUIAdapters).filter(eventOrganizationFilter._2(organization)).map(uiAdapterToFlavor)
            .filter(isNotEpisodeDublinCore).toList();
  }

  @Override
  public void repopulate() throws IndexRebuildException {
    try {
      final int total;
      try {
        total = persistence.countEvents();
      } catch (SchedulerServiceDatabaseException e) {
        logIndexRebuildError(logger, index.getIndexName(), e);
        throw new IndexRebuildException(index.getIndexName(), getService(), e);
      }
      logIndexRebuildBegin(logger, index.getIndexName(), total, "scheduled events");
      final int[] current = {0};
      int n = 20;
      var updatedEventRange = new ArrayList<Event>();

      for (Organization organization: orgDirectoryService.getOrganizations()) {
        final User user = SecurityUtil.createSystemUser(systemUserName, organization);
        SecurityUtil.runAs(securityService, organization, user,
                () -> {
                  final List<ExtendedEventDto> events;
                  try {
                    events = persistence.getEvents();
                  } catch (SchedulerServiceDatabaseException e) {
                    logIndexRebuildError(logger, index.getIndexName(), e, organization);
                    return;
                  }

                  for (ExtendedEventDto event : events) {
                    try {
                      current[0]++;

                      var updatedEventData = Optional.of(new Event(event.getMediaPackageId(), organization.getId()));
                      updatedEventData = getEventUpdateFunction(event, organization.getId(),
                                  securityService.getUser()).apply(updatedEventData);
                      updatedEventRange.add(updatedEventData.get());

                      if (updatedEventRange.size() >= n || current[0] >= events.size()) {
                        index.bulkEventUpdate(updatedEventRange);
                        logIndexRebuildProgress(logger, index.getIndexName(), total, current[0], n);
                        updatedEventRange.clear();
                      }
                    } catch (SearchIndexException e) {
                      logger.error("Error while updating event '{}' from search index:", event.getMediaPackageId(), e);
                    }
                  }
               });
      }
    } catch (Exception e) {
      logIndexRebuildError(logger, index.getIndexName(), e);
      throw new IndexRebuildException(index.getIndexName(), getService(), e);
    }
  }

  @Override
  public IndexRebuildService.Service getService() {
    return IndexRebuildService.Service.Scheduler;
  }

  public SecurityService getSecurityService() {
    return securityService;
  }
  /**
   * Get the function to update a scheduled event in the Elasticsearch index.
   *
   * @param scheduledEvent
   *          The theme to update
   * @param orgId
   *          The id of the current organization
   * @param user
   *          The user
   * @return the function to do the update
   */
  private Function<Optional<Event>, Optional<Event>> getEventUpdateFunction(ExtendedEventDto scheduledEvent,
          String orgId, User user) {
    return (Optional<Event> eventOpt) -> {
      Event event = eventOpt.orElse(new Event(scheduledEvent.getMediaPackageId(), orgId));
      final Set<String> presenters = getPresenters(Opt.nul(scheduledEvent.getPresenters()).getOr(""));
      final Map<String, String> caMetadata = deserializeExtendedEventProperties(scheduledEvent.
              getCaptureAgentProperties());
      AQueryBuilder query = assetManager.createQuery();
      final AResult result = query.select(query.snapshot())
              .where(query.mediaPackageId(scheduledEvent.getMediaPackageId()).and(query.version().isLatest())).run();
      final Snapshot snapshot = result.getRecords().head().get().getSnapshot().get();

      Opt<AccessControlList> acl = Opt.some(authorizationService.getActiveAcl(snapshot.getMediaPackage()).getA());
      Opt<DublinCoreCatalog> dublinCore = loadEpisodeDublinCoreFromAsset(snapshot);
      Opt<Date> startTime = Opt.some(scheduledEvent.getStartDate());
      Opt<Date> endTime = Opt.some(scheduledEvent.getEndDate());
      Opt<Set<String>> presentersOpt = Opt.some(presenters);
      Opt<String> agentId = Opt.some(scheduledEvent.getCaptureAgentId());
      Opt<Map<String, String>> properties = Opt.some(caMetadata);
      Opt<String> recordingStatus = Opt.nul(scheduledEvent.getRecordingState());

      if (acl.isSome()) {
        event.setAccessPolicy(AccessControlParser.toJsonSilent(acl.get()));
      }
      if (dublinCore.isSome()) {
        EventIndexUtils.updateEvent(event, dublinCore.get());
        if (isBlank(event.getCreator()))
          event.setCreator(getSecurityService().getUser().getName());

        // Update series name if not already done
        try {
          EventIndexUtils.updateSeriesName(event, orgId, user, index);
        } catch (SearchIndexException e) {
          logger.error("Error updating the series name of the event {} in the {} index.",
                  scheduledEvent.getMediaPackageId(), index.getIndexName(), e);
        }
      }
      if (presentersOpt.isSome()) {
        event.setTechnicalPresenters(new ArrayList<>(presentersOpt.get()));
      }
      if (agentId.isSome()) {
        event.setAgentId(agentId.get());
      }
      if (recordingStatus.isSome() && !recordingStatus.get().equals(RecordingState.UNKNOWN)) {
        event.setRecordingStatus(recordingStatus.get());
      }
      if (properties.isSome()) {
        event.setAgentConfiguration(properties.get());
      }
      if (startTime.isSome()) {
        String startTimeStr = startTime == null ? null : DateTimeSupport.toUTC(startTime.get().getTime());
        event.setTechnicalStartTime(startTimeStr);
      }
      if (endTime.isSome()) {
        String endTimeStr = endTime == null ? null : DateTimeSupport.toUTC(endTime.get().getTime());
        event.setTechnicalEndTime(endTimeStr);
      }

      return Optional.of(event);
    };
  }
}
