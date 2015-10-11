package to.rtc.rtc2jira.exporter.jira;

import static to.rtc.rtc2jira.storage.Field.of;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.core.MediaType;

import to.rtc.rtc2jira.Settings;
import to.rtc.rtc2jira.exporter.Exporter;
import to.rtc.rtc2jira.exporter.jira.entities.BulkCreateContainer;
import to.rtc.rtc2jira.exporter.jira.entities.BulkCreateEntry;
import to.rtc.rtc2jira.exporter.jira.entities.BulkCreateResponseEntity;
import to.rtc.rtc2jira.exporter.jira.entities.Issue;
import to.rtc.rtc2jira.exporter.jira.entities.IssueAttachment;
import to.rtc.rtc2jira.exporter.jira.entities.IssueComment;
import to.rtc.rtc2jira.exporter.jira.entities.IssueFields;
import to.rtc.rtc2jira.exporter.jira.entities.IssueResolution;
import to.rtc.rtc2jira.exporter.jira.entities.IssueSearch;
import to.rtc.rtc2jira.exporter.jira.entities.IssueSearch.IssueSearchResult;
import to.rtc.rtc2jira.exporter.jira.entities.IssueStatus;
import to.rtc.rtc2jira.exporter.jira.entities.JiraUser;
import to.rtc.rtc2jira.exporter.jira.entities.Project;
import to.rtc.rtc2jira.exporter.jira.entities.ResolutionEnum;
import to.rtc.rtc2jira.exporter.jira.entities.StatusEnum;
import to.rtc.rtc2jira.exporter.jira.mapping.MappingRegistry;
import to.rtc.rtc2jira.exporter.jira.mapping.WorkItemTypeMapping;
import to.rtc.rtc2jira.storage.Attachment;
import to.rtc.rtc2jira.storage.AttachmentStorage;
import to.rtc.rtc2jira.storage.Comment;
import to.rtc.rtc2jira.storage.FieldNames;
import to.rtc.rtc2jira.storage.StorageEngine;
import to.rtc.rtc2jira.storage.StorageQuery;

import com.orientechnologies.orient.core.record.impl.ODocument;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.ClientResponse.Status;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.multipart.FormDataMultiPart;
import com.sun.jersey.multipart.file.FileDataBodyPart;

public class JiraExporter implements Exporter {
  private static final Logger LOGGER = Logger.getLogger(JiraExporter.class.getName());
  public static final JiraExporter INSTANCE;
  private StorageEngine store;
  private Settings settings;
  private JiraRestAccess restAccess;
  private Optional<Project> projectOptional;
  private int highestExistingId = -1;
  private MappingRegistry mappingRegistry;
  private WorkItemTypeMapping workItemTypeMapping;
  private Set<String> createdUsers = new HashSet<String>(500);
  private Set<String> existingUsers = new HashSet<String>(500);

  static {
    INSTANCE = new JiraExporter();
  }

  private JiraExporter() {};

  @Override
  public boolean isConfigured() {
    return Settings.getInstance().hasJiraProperties();
  }

  @Override
  public void initialize(Settings settings, StorageEngine store) throws Exception {
    this.settings = settings;
    this.store = store;
    setRestAccess(new JiraRestAccess(settings.getJiraUrl(), settings.getJiraUser(), settings.getJiraPassword()));
    ClientResponse response = getRestAccess().get("/myself");
    // ClientResponse response = getRestAccess().get("/issue/WOR-137");

    if (response.getStatus() != Status.OK.getStatusCode()) {
      throw new RuntimeException("Unable to connect to jira repository: " + response.toString());
    }
    this.projectOptional = getProject();
    mappingRegistry = new MappingRegistry();
    this.workItemTypeMapping = new WorkItemTypeMapping();
  }

  @Override
  public void createOrUpdateItem(ODocument item) throws Exception {
    String workItemId = item.field(FieldNames.ID);
    ensureWorkItemWithId(Integer.parseInt(workItemId));
    Date modified = StorageQuery.getField(item, FieldNames.MODIFIED, Date.from(Instant.now()));
    Date lastExport = StorageQuery.getField(item, FieldNames.JIRA_EXPORT_TIMESTAMP, new Date(0));
    if (Settings.getInstance().isForceUpdate() || modified.compareTo(lastExport) > 0) {
      updateItem(item);
    }
  }

  private void ensureWorkItemWithId(int workItemId) throws Exception {
    if (highestExistingId == -1) {
      IssueSearchResult searchResult =
          IssueSearch.INSTANCE.run("project = '" + settings.getJiraProjectKey() + "' ORDER BY id DESC");
      if (searchResult.getTotal() > 0) {
        Issue last = searchResult.getIssues().get(0);
        highestExistingId = extractId(last.getKey());
      } else {
        highestExistingId = 0;
      }
    }

    while (highestExistingId < workItemId) {
      int gap = workItemId - highestExistingId;
      gap = (gap <= 100) ? gap : 100;
      createDummyIssues(gap);
    }
  }

  private void createDummyIssues(int total) throws Exception {
    if (projectOptional.isPresent()) {
      // build request entity
      Project project = projectOptional.get();
      BulkCreateContainer postEntity = new BulkCreateContainer();
      List<BulkCreateEntry> issueUpdates = postEntity.getIssueUpdates();
      for (int i = 0; i < total; i++) {
        Issue issue = new Issue();
        IssueFields fields = issue.getFields();
        fields.setProject(project);
        fields.setIssuetype(workItemTypeMapping.getIssueType("Task", project));
        fields.setSummary("Dummy");
        fields.setDescription("This is just a dummy issue. Delete it after successfully migrating to Jira.");
        issueUpdates.add(new BulkCreateEntry(fields));
      }
      // post request
      long startTime = System.currentTimeMillis();
      LOGGER.log(Level.INFO, "Starting bulk creation of " + total + " items.");
      ClientResponse postResponse = getRestAccess().post("/issue/bulk", postEntity);
      if (postResponse.getStatus() == Status.CREATED.getStatusCode()) {
        BulkCreateResponseEntity respEntity = postResponse.getEntity(BulkCreateResponseEntity.class);
        List<Issue> issues = respEntity.getIssues();
        if (!issues.isEmpty()) {
          highestExistingId = extractId(issues.get(issues.size() - 1).getKey());
        }
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double minutes = Math.floor(duration / (1000 * 60));
        double seconds = (duration % (1000 * 60)) / 1000;
        LOGGER.log(Level.INFO, "Bulk creation of " + issues.size() + " items took " + (int) minutes + " min. and "
            + (int) seconds + " sec.");
      } else {
        String errorMessage = "Problems while bulk creating issues: " + postResponse.getEntity(String.class);
        throw new Exception(errorMessage);
      }
    }
  }

  private void updateItem(ODocument item) throws Exception {
    if (projectOptional.isPresent()) {
      Project project = projectOptional.get();
      Issue issue = createIssueFromWorkItem(item, project);
      persistIssue(item, issue);
      persistNewComments(item, issue);
      try {
        persistAttachments(item, issue);
      } catch (IOException e) {
        throw new Exception("Fatal error - could not open attachment directory while exporting", e);
      }
    }
  }

  private String getKey(ODocument item) {
    String key = null;
    if (projectOptional.isPresent()) {
      String id = item.field(FieldNames.ID);
      key = settings.getJiraProjectKey() + '-' + id;
    }
    return key;
  }

  private int extractId(String key) {
    String[] split = key.split("-");
    return Integer.parseInt(split[1]);
  }

  private void persistIssue(ODocument item, Issue issue) {
    Issue lastExportedIssue = getLastExportedInfo(item);
    boolean success = updateIssueInJira(issue, lastExportedIssue);
    if (success) {
      storeReference(issue, item);
      cacheInfoOfLastExport(issue, item);
    }
  }

  private Issue getLastExportedInfo(ODocument item) {
    String lastExportedStatus = item.field(FieldNames.JIRA_LAST_EXPORTED_STATUS);
    Issue lastExportedIssue = new Issue();
    // status
    IssueStatus status;
    if (lastExportedStatus == null || lastExportedStatus.isEmpty()) {
      status = IssueStatus.createToDo();
    } else {
      StatusEnum statusEnum = StatusEnum.forJiraId(lastExportedStatus);
      status = statusEnum.getIssueStatus();
    }
    lastExportedIssue.getFields().setStatus(status);
    return lastExportedIssue;
  }

  private void persistAttachments(ODocument item, Issue issue) throws IOException {
    AttachmentStorage storage = new AttachmentStorage();
    String id = item.field(FieldNames.ID);
    List<Attachment> attachments = storage.readAttachments(Long.parseLong(id));
    if (attachments.size() > 0) {
      List<String> alreadyExportedAttachments = item.field(Attachment.EXPORTED_ATTACHMENTS_PROPERTY);
      final FormDataMultiPart multiPart = new FormDataMultiPart();
      int newlyAdded = 0;
      for (Attachment attachment : attachments) {
        // check if already exported
        if (!alreadyExportedAttachments.contains(attachment.getPath().getFileName().toString())) {
          final File fileToUpload = attachment.getPath().toFile();
          if (fileToUpload != null) {
            multiPart.bodyPart(new FileDataBodyPart("file", fileToUpload, MediaType.APPLICATION_OCTET_STREAM_TYPE));
            newlyAdded++;
          }
        }
      }
      if (newlyAdded > 0) {
        ClientResponse clientResponse = getRestAccess().postMultiPart(issue.getSelfPath() + "/attachments", multiPart);
        if (isResponseOk(clientResponse)) {
          // refresh list of already exported attachments
          List<IssueAttachment> responseAttachments =
              clientResponse.getEntity(new GenericType<List<IssueAttachment>>() {});
          for (IssueAttachment issueAttachment : responseAttachments) {
            alreadyExportedAttachments.add(issueAttachment.getFilename());
          }
          store.setFields(item, //
              of(Attachment.EXPORTED_ATTACHMENTS_PROPERTY, alreadyExportedAttachments));
        }
      }
    }
  }

  private void persistNewComments(ODocument item, Issue issue) {
    List<IssueComment> issueComments = issue.getFields().getComment().getComments();
    List<Comment> comments = item.field(FieldNames.COMMENTS);
    if (comments != null) {
      for (Comment comment : comments) {
        IssueComment issueComment = IssueComment.createWithIdAndBody(issue, comment.getJiraId(), comment.getComment());
        if (comment.getJiraId() == null) {
          JiraUser jiraUser = persistUser(comment);
          issueComment.setAuthor(jiraUser);
          issueComment.setCreated(comment.getDate());
          ClientResponse cr = getRestAccess().post(issueComment.getPath(), issueComment);
          IssueComment issueCommentPosted = cr.getEntity(IssueComment.class);
          issueComment.setId(issueCommentPosted.getId());
          issueCommentPosted.setIssue(issue);
          // update document comment
          comment.setJiraId(issueComment.getId());
        }
        issueComments.add(issueComment);
      }
      // save comments in item because IDs may have been added
      store.setFields(item, //
          of(FieldNames.COMMENTS, comments));
    }
  }

  private JiraUser persistUser(Comment comment) {
    JiraUser jiraUser = JiraUser.createFromComment(comment);
    ClientResponse cr = getRestAccess().get(jiraUser.getSelfPath());
    if (!isResponseOk(cr)) {
      ClientResponse postResponse = getRestAccess().post(jiraUser.getPath(), jiraUser);
      if (isResponseOk(postResponse)) {
        jiraUser = postResponse.getEntity(JiraUser.class);
      }
    }
    return jiraUser;
  }

  void storeReference(Issue jiraIssue, ODocument workItem) {
    store.setFields(workItem, //
        of(FieldNames.JIRA_KEY_LINK, jiraIssue.getKey()), //
        of(FieldNames.JIRA_ID_LINK, jiraIssue.getId()));
  }

  void cacheInfoOfLastExport(Issue jiraIssue, ODocument workItem) {
    store.setFields(
        workItem, //
        of(FieldNames.JIRA_EXPORT_TIMESTAMP,
            StorageQuery.getField(workItem, FieldNames.MODIFIED, Date.from(Instant.now()))),
        of(FieldNames.JIRA_LAST_EXPORTED_STATUS, jiraIssue.getFields().getStatus().getId()));
  }

  private Optional<Project> getProject() {
    Project projectConfig = new Project();
    projectConfig.setKey(settings.getJiraProjectKey());
    return Optional.ofNullable(getRestAccess().get(projectConfig.getSelfPath(), Project.class));
  }

  Issue createIssueInJira(Issue issue) {
    ClientResponse postResponse = getRestAccess().post(issue.getPath(), issue);
    if (postResponse.getStatus() == Status.CREATED.getStatusCode()) {
      return postResponse.getEntity(Issue.class);
    } else {
      System.err.println("Problems while creating issue: " + postResponse.getEntity(String.class));
      return null;
    }
  }

  private boolean updateIssueInJira(Issue issue, Issue lastExportedIssue) {
    ClientResponse postResponse = getRestAccess().put("/issue/" + issue.getKey(), issue);
    if (isResponseOk(postResponse)) {
      boolean result = true;
      String transitionId = getTransitionId(issue.getFields().getStatus(), lastExportedIssue.getFields().getStatus());
      if (!StatusEnum.NO_TRANSITION.equals(transitionId)) {
        result = doTransition(issue, transitionId);
      }
      return result;
    } else {
      System.err.println("Problems while updating issue: " + postResponse.getEntity(String.class));
      return false;
    }
  }

  String getTransitionId(IssueStatus targetStatus, IssueStatus currentStatus) {
    return currentStatus.getStatusEnum().getTransitionId(targetStatus.getStatusEnum());
  }

  private boolean doTransition(Issue issue, String transitionId) {
    String entity = "{\"transition\":{\"id\":" + transitionId + "}}";
    ClientResponse postResponse =
        getRestAccess().post("/issue/" + issue.getKey() + "/transitions?expand=transitions.fields", entity);
    if (isResponseOk(postResponse)) {
      return true;
    } else {
      System.err.println("Problems while transitioning issue: " + postResponse.getEntity(String.class));
      return false;
    }
  }

  boolean forceUpdate() {
    return Settings.getInstance().isForceUpdate();
  }

  Issue createIssueFromWorkItem(ODocument workItem, Project project) {
    Issue issue = new Issue();
    String id = workItem.field(FieldNames.ID);
    issue.setId(id);
    String key = getKey(workItem);
    issue.setKey(key);
    ClientResponse cr = getRestAccess().get(issue.getSelfPath());
    if (cr.getStatus() == 200) {
      issue = cr.getEntity(Issue.class);
      IssueFields issueFields = issue.getFields();
      issueFields.setProject(project);
      mappingRegistry.map(workItem, issue, store);
      // set resolution to appropriate default, otherwise it will be set to "fixed" whenever status
      // is "done", even if issue is not a defect
      if (issueFields.getStatus().getStatusEnum() == StatusEnum.done && issueFields.getResolution() == null) {
        issueFields.setResolution(new IssueResolution(ResolutionEnum.done));
      }
    } else {
      issue = null;
      LOGGER.log(Level.SEVERE, "A problem occurred while retrieving an issue: " + cr.getEntity(String.class));
    }
    return issue;
  }


  private boolean isResponseOk(ClientResponse cr) {
    return cr.getStatus() >= Status.OK.getStatusCode() && cr.getStatus() <= Status.PARTIAL_CONTENT.getStatusCode();
  }

  public JiraRestAccess getRestAccess() {
    return restAccess;
  }

  private void setRestAccess(JiraRestAccess restAccess) {
    this.restAccess = restAccess;
  }

  @Override
  public void postExport() throws Exception {
    // deactivate new users during testing phase (lower costs)
    for (String email : createdUsers) {
      JiraUser jiraUser = new JiraUser();
      jiraUser.setEmailAddress(email);
      String name = email.split("@")[0];
      jiraUser.setName(name);
      jiraUser.setKey(name);
      jiraUser.setActive(false);
      ClientResponse putResponse = getRestAccess().delete("/group/user?groupname=jira-users&username=" + name);
      if (putResponse.getStatus() != 200) {
        LOGGER.log(Level.SEVERE, "Problems while removing user " + jiraUser.getEmailAddress()
            + "  from jira-users group. " + putResponse.getEntity(String.class));
      }
    }
  }

  public void onCreateUser(JiraUser newUser) {
    createdUsers.add(newUser.getEmailAddress());
  }

  public Set<String> getExistingUsers() {
    return existingUsers;
  }

}
