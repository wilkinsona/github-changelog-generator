/*
 * Copyright 2018-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.spring.githubchangeloggenerator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.spring.githubchangeloggenerator.ApplicationProperties.IssueSort;
import io.spring.githubchangeloggenerator.github.payload.Issue;
import io.spring.githubchangeloggenerator.github.payload.Label;
import io.spring.githubchangeloggenerator.github.payload.User;
import io.spring.githubchangeloggenerator.github.service.GitHubService;
import io.spring.githubchangeloggenerator.github.service.Repository;

import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

/**
 * Generates a changelog markdown file which includes bug fixes, enhancements and
 * contributors for a given milestone.
 *
 * @author Madhura Bhave
 * @author Phillip Webb
 */
@Component
public class ChangelogGenerator {

	private static final String THANK_YOU = "## :heart: Contributors\n\n"
			+ "We'd like to thank all the contributors who worked on this release!";

	private static final Comparator<Issue> TITLE_COMPARATOR = Comparator.comparing(Issue::getTitle,
			String.CASE_INSENSITIVE_ORDER);

	private static final Pattern ghUserMentionPattern = Pattern.compile("(^|[^\\w`])(@[\\w-]+)");

	private final GitHubService service;

	private final Repository repository;

	private final MilestoneReference milestoneReference;

	private final IssueSort sort;

	private final Set<String> excludeLabels;

	private final ChangelogSections sections;

	public ChangelogGenerator(GitHubService service, ApplicationProperties properties) {
		this.service = service;
		this.repository = properties.getRepository();
		this.milestoneReference = properties.getMilestoneReference();
		this.sort = properties.getIssues().getSort();
		this.excludeLabels = properties.getIssues().getExcludes().getLabels();
		this.sections = new ChangelogSections(properties);
	}

	/**
	 * Generates a file at the given path which includes bug fixes, enhancements and
	 * contributors for the given milestone.
	 * @param milestone the milestone to generate the changelog for
	 * @param path the path to the file
	 * @throws IOException if writing to file failed
	 */
	public void generate(String milestone, String path) throws IOException {
		int milestoneNumber = resolveMilestoneReference(milestone);
		List<Issue> issues = getIssues(milestoneNumber);
		String content = generateContent(issues);
		writeContentToFile(content, path);
	}

	private List<Issue> getIssues(int milestoneNumber) {
		List<Issue> issues = new ArrayList<>(this.service.getIssuesForMilestone(milestoneNumber, this.repository));
		issues.removeIf(this::isExcluded);
		return issues;
	}

	private boolean isExcluded(Issue issue) {
		return issue.getLabels().stream().anyMatch(this::isExcluded);
	}

	private boolean isExcluded(Label label) {
		return this.excludeLabels.contains(label.getName());
	}

	private int resolveMilestoneReference(String milestone) {
		switch (this.milestoneReference) {
		case TITLE:
			return this.service.getMilestoneNumber(milestone, this.repository);
		case ID:
			return Integer.parseInt(milestone);
		default:
			throw new IllegalStateException("Unsupported milestone reference value " + this.milestoneReference);
		}
	}

	private String generateContent(List<Issue> issues) {
		StringBuilder content = new StringBuilder();
		addSectionContent(content, this.sections.collate(issues));
		Set<User> contributors = getContributors(issues);
		if (!contributors.isEmpty()) {
			addContributorsContent(content, contributors);
		}
		return content.toString();
	}

	private void addSectionContent(StringBuilder content, Map<ChangelogSection, List<Issue>> sectionIssues) {
		sectionIssues.forEach((section, issues) -> {
			sort(section.getSort(), issues);
			content.append((content.length() != 0) ? "\n" : "");
			content.append("## ").append(section).append("\n\n");
			issues.stream().map(this::getFormattedIssue).forEach(content::append);
		});
	}

	private void sort(IssueSort sort, List<Issue> issues) {
		sort = (sort != null) ? sort : this.sort;
		if (sort == IssueSort.TITLE) {
			issues.sort(TITLE_COMPARATOR);
		}
	}

	private String getFormattedIssue(Issue issue) {
		String title = issue.getTitle();
		title = ghUserMentionPattern.matcher(title).replaceAll("$1`$2`");
		return "- " + title + " " + getLinkToIssue(issue) + "\n";
	}

	private String getLinkToIssue(Issue issue) {
		return "[#" + issue.getNumber() + "]" + "(" + issue.getUrl() + ")";
	}

	private Set<User> getContributors(List<Issue> issues) {
		return issues.stream().filter((issue) -> issue.getPullRequest() != null).map(Issue::getUser)
				.collect(Collectors.toSet());
	}

	private void addContributorsContent(StringBuilder content, Set<User> contributors) {
		content.append("\n" + THANK_YOU + "\n\n");
		contributors.stream().map(this::formatContributors).forEach(content::append);
	}

	private String formatContributors(User c) {
		return "- [@" + c.getName() + "]" + "(" + c.getUrl() + ")\n";
	}

	private void writeContentToFile(String content, String path) throws IOException {
		FileCopyUtils.copy(content, new FileWriter(new File(path)));
	}

}