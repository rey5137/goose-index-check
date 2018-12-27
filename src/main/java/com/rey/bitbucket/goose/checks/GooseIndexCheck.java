package com.rey.bitbucket.goose.checks;

import com.atlassian.bitbucket.commit.CommitService;
import com.atlassian.bitbucket.content.ChangeType;
import com.atlassian.bitbucket.content.ContentService;
import com.atlassian.bitbucket.content.ContentTreeCallback;
import com.atlassian.bitbucket.content.ContentTreeContext;
import com.atlassian.bitbucket.content.ContentTreeNode;
import com.atlassian.bitbucket.content.ContentTreeSummary;
import com.atlassian.bitbucket.content.Path;
import com.atlassian.bitbucket.hook.repository.PreRepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.PullRequestMergeHookRequest;
import com.atlassian.bitbucket.hook.repository.RepositoryHookResult;
import com.atlassian.bitbucket.hook.repository.RepositoryMergeCheck;
import com.atlassian.bitbucket.i18n.I18nService;
import com.atlassian.bitbucket.pull.PullRequestChangesRequest;
import com.atlassian.bitbucket.pull.PullRequestService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.util.PageUtils;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component("gooseIndexCheck")
public class GooseIndexCheck implements RepositoryMergeCheck {

    private final I18nService i18nService;
    private final CommitService commitService;
    private final ContentService contentService;
    private final PullRequestService pullRequestService;

    private static final String FILE_NAME_PATTERN = "(\\d*)_.*.sql";

    @Autowired
    public GooseIndexCheck(@ComponentImport I18nService i18nService,
                           @ComponentImport CommitService commitService,
                           @ComponentImport ContentService contentService,
                           @ComponentImport PullRequestService pullRequestService) {
        this.i18nService = i18nService;
        this.commitService = commitService;
        this.contentService = contentService;
        this.pullRequestService = pullRequestService;
    }

    @Nonnull
    public RepositoryHookResult preUpdate(@Nonnull PreRepositoryHookContext context,
                                          @Nonnull PullRequestMergeHookRequest request) {
        Repository repository = request.getPullRequest().getToRef().getRepository();
        PullRequestChangesRequest pullRequestChangesRequest = new PullRequestChangesRequest.Builder(repository.getId(), request.getPullRequest().getId())
                .build();
        HashMap<String, List<GooseFile>> directoryMap = new HashMap<>();
        StringBuilder builder = new StringBuilder();
        pullRequestService.streamChanges(pullRequestChangesRequest, change -> {
            Path changePath = change.getPath();
            if (change.getType() == ChangeType.ADD) {
                int gooseIndex = extractGooseIndex(changePath.getName());
                if(gooseIndex >= 0) {
                    List<GooseFile> gooseFiles = getGooseFiles(directoryMap, repository, request.getToRef().getId(), changePath.getParent());
                    Path existedPath = findPathByIndex(gooseFiles, gooseIndex);
                    if (existedPath != null)
                        builder.append("- Duplicate index of file: ")
                                .append(changePath.toString())
                                .append("\n with existed file: ")
                                .append(existedPath.getName())
                                .append("\n");
                }

            }
            return true;
        });

        if(builder.length() == 0)
            return RepositoryHookResult.accepted();
        else
            return RepositoryHookResult.rejected("Duplicate goose index", builder.toString());
    }

    private int extractGooseIndex(String name) {
        Matcher matcher = Pattern.compile(FILE_NAME_PATTERN).matcher(name);
        if(matcher.matches())
            return Integer.parseInt(matcher.group(1));
        else
            return -1;
    }

    private List<GooseFile> getGooseFiles(Map<String, List<GooseFile>> map, Repository repository, String objectId, String directoryPath) {
        if (map.containsKey(directoryPath))
            return map.get(directoryPath);

        List<GooseFile> gooseFiles = new ArrayList<>();
        contentService.streamDirectory(repository, objectId, directoryPath, false, new ContentTreeCallback() {

            @Override
            public void onEnd(@Nonnull ContentTreeSummary contentTreeSummary) {
            }

            @Override
            public void onStart(@Nonnull ContentTreeContext contentTreeContext) {
            }

            @Override
            public boolean onTreeNode(@Nonnull ContentTreeNode contentTreeNode) {
                Path path = contentTreeNode.getPath();
                int index = extractGooseIndex(path.getName());
                if(index >= 0)
                    gooseFiles.add(new GooseFile(path, index));
                return true;
            }
        }, PageUtils.newRequest(0, 999999));
        gooseFiles.sort(Comparator.comparingInt(d -> -d.index));
        map.put(directoryPath, gooseFiles);
        return gooseFiles;
    }

    private Path findPathByIndex(List<GooseFile> gooseFiles, int index) {
        for(GooseFile gooseFile : gooseFiles) {
            if(gooseFile.index == index)
                return gooseFile.path;
        }
        return null;
    }

    class GooseFile {
        Path path;
        int index;

        GooseFile(Path path, int index) {
            this.path = path;
            this.index = index;
        }
    }
}
