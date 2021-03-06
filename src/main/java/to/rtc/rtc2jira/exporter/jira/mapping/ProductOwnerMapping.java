/*
 * Copyright (c) 2015 BISON Schweiz AG, All Rights Reserved.
 */
package to.rtc.rtc2jira.exporter.jira.mapping;

import to.rtc.rtc2jira.exporter.jira.entities.Issue;
import to.rtc.rtc2jira.exporter.jira.entities.JiraUser;
import to.rtc.rtc2jira.importer.mapping.ContributorMapping;
import to.rtc.rtc2jira.storage.StorageEngine;

/**
 * @author gustaf.hansen
 *
 */
public class ProductOwnerMapping extends BaseUserMapping {

  @Override
  public void map(Object value, Issue issue, StorageEngine storage) {
    String formattedStr = (String) value;
    if (formattedStr != null) {
      JiraUser jiraUser = getUser(formattedStr);
      issue.getFields().setProductOwner(jiraUser);
    }
  }

  private JiraUser getUser(String contributorStr) {
    JiraUser jiraUser = ContributorMapping.stringToUser(contributorStr);
    return getUser(jiraUser);
  }


}
