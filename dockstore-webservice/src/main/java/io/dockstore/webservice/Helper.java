/*
 * Copyright (C) 2015 Collaboratory
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.dockstore.webservice;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.WebApplicationException;

import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.eclipse.egit.github.core.client.GitHubClient;
import org.eclipse.egit.github.core.service.ContentsService;
import org.eclipse.egit.github.core.service.RepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.gson.Gson;

import io.dockstore.webservice.core.Container;
import io.dockstore.webservice.core.SourceFile;
import io.dockstore.webservice.core.Tag;
import io.dockstore.webservice.core.Token;
import io.dockstore.webservice.core.TokenType;
import io.dockstore.webservice.core.User;
import io.dockstore.webservice.helpers.SourceCodeRepoFactory;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface;
import io.dockstore.webservice.helpers.SourceCodeRepoInterface.FileResponse;
import io.dockstore.webservice.jdbi.ContainerDAO;
import io.dockstore.webservice.jdbi.FileDAO;
import io.dockstore.webservice.jdbi.TagDAO;
import io.dockstore.webservice.jdbi.TokenDAO;
import io.dockstore.webservice.jdbi.UserDAO;
import io.dockstore.webservice.resources.ResourceUtilities;

/**
 *
 * @author xliu
 */
public class Helper {
    private static final Logger LOG = LoggerFactory.getLogger(Helper.class);

    private static final String QUAY_URL = "https://quay.io/api/v1/";
    private static final String BITBUCKET_URL = "https://bitbucket.org/";

    private static final String DOCKSTORE_CWL = "Dockstore.cwl";
    private static final String DOCKERFILE = "Dockerfile";

    public static class RepoList {

        private List<Container> repositories;

        public void setRepositories(List<Container> repositories) {
            this.repositories = repositories;
        }

        public List<Container> getRepositories() {
            return this.repositories;
        }
    }

    private static void updateTags(Container container, TagDAO tagDAO, FileDAO fileDAO, Map<String, List<Tag>> tagMap) {
        List<Tag> existingTags = new ArrayList(container.getTags());
        List<Tag> newTags = tagMap.get(container.getPath());
        Map<String, Set<SourceFile>> fileMap = new HashMap<>();

        if (newTags == null) {
            LOG.info("Tags for container " + container.getPath() + " did not get updated because new tags were not found");
            return;
        }

        List<Tag> toDelete = new ArrayList<>(0);
        for (Iterator<Tag> iterator = existingTags.iterator(); iterator.hasNext();) {
            Tag oldTag = iterator.next();
            boolean exists = false;
            for (Tag newTag : newTags) {
                if (newTag.getName().equals(oldTag.getName())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                toDelete.add(oldTag);
                iterator.remove();
            }
        }

        for (Tag newTag : newTags) {
            boolean exists = false;

            // Find if user already has the container
            for (Tag oldTag : existingTags) {
                if (newTag.getName().equals(oldTag.getName())) {
                    exists = true;

                    oldTag.update(newTag);

                    // tagDAO.create(oldTag);

                    break;
                }
            }

            // Tag does not already exist
            if (!exists) {
                LOG.info("Tag " + newTag.getName() + " is added to " + container.getPath());

                // long id = tagDAO.create(newTag);
                // Tag tag = tagDAO.findById(id);
                // container.addTag(tag);

                existingTags.add(newTag);
            }

            fileMap.put(newTag.getName(), newTag.getSourceFiles());
        }

        for (Tag tag : existingTags) {
            Set<SourceFile> newFiles = fileMap.get(tag.getName());
            Set<SourceFile> oldFiles = tag.getSourceFiles();

            for (SourceFile newFile : newFiles) {
                boolean exists = false;
                for (SourceFile oldFile : oldFiles) {
                    if (oldFile.getType().equals(newFile.getType())) {
                        exists = true;

                        oldFile.update(newFile);
                        fileDAO.create(oldFile);
                    }
                }

                if (!exists) {
                    long id = fileDAO.create(newFile);
                    SourceFile file = fileDAO.findById(id);
                    tag.addSourceFile(file);

                    oldFiles.add(newFile);
                }
            }

            long id = tagDAO.create(tag);
            tag = tagDAO.findById(id);
            container.addTag(tag);
        }

    }

    /**
     * Updates the new list of containers to the database. Deletes containers that has no users.
     * 
     * @param newList
     * @param currentList
     * @param user
     * @param containerDAO
     * @param tagDAO
     * @param fileDAO
     * @param tagMap
     * @return list of newly updated containers
     */
    private static List<Container> updateContainers(List<Container> newList, List<Container> currentList, User user,
            ContainerDAO containerDAO, TagDAO tagDAO, FileDAO fileDAO, Map<String, List<Tag>> tagMap) {
        Date time = new Date();

        List<Container> toDelete = new ArrayList<>(0);
        // Find containers that the user no longer has
        for (Iterator<Container> iterator = currentList.iterator(); iterator.hasNext();) {
            Container oldContainer = iterator.next();
            boolean exists = false;
            for (Container newContainer : newList) {
                if (newContainer.getName().equals(oldContainer.getName())
                        && newContainer.getNamespace().equals(oldContainer.getNamespace())
                        && newContainer.getRegistry().equals(oldContainer.getRegistry())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                oldContainer.removeUser(user);
                // user.removeContainer(oldContainer);
                toDelete.add(oldContainer);
                iterator.remove();
            }
        }

        for (Container newContainer : newList) {
            String path = newContainer.getRegistry() + "/" + newContainer.getNamespace() + "/" + newContainer.getName();
            boolean exists = false;

            // Find if user already has the container
            for (Container oldContainer : currentList) {
                if (newContainer.getPath().equals(oldContainer.getPath())) {
                    exists = true;

                    oldContainer.update(newContainer);

                    break;
                }
            }

            // Find if container already exists, but does not belong to user
            if (!exists) {
                Container oldContainer = containerDAO.findByPath(path);
                if (oldContainer != null) {
                    exists = true;
                    oldContainer.update(newContainer);
                    currentList.add(oldContainer);
                }
            }

            // Container does not already exist
            if (!exists) {
                // newContainer.setUserId(userId);
                newContainer.setPath(path);

                currentList.add(newContainer);
            }
        }

        // Save all new and existing containers, and generate new tags
        for (Container container : currentList) {
            container.setLastUpdated(time);
            container.addUser(user);
            containerDAO.create(container);

            // container.getTags().clear();
            //
            // List<Tag> tags = tagMap.get(container.getPath());
            // if (tags != null) {
            // for (Tag tag : tags) {
            // for (SourceFile file : tag.getSourceFiles()) {
            // fileDAO.create(file);
            // }
            //
            // long tagId = tagDAO.create(tag);
            // tag = tagDAO.findById(tagId);
            // container.addTag(tag);
            // }
            // }
            updateTags(container, tagDAO, fileDAO, tagMap);
            LOG.info("UPDATED Container: " + container.getPath());
        }

        // delete container if it has no users
        for (Container c : toDelete) {
            LOG.info(c.getPath() + " " + c.getUsers().size());

            if (c.getUsers().isEmpty()) {
                LOG.info("DELETING: " + c.getPath());
                c.getTags().clear();
                containerDAO.delete(c);
            }
        }

        return currentList;
    }

    /**
     * Retrieve the list of user's repositories from Quay.io.
     * 
     * @param client
     * @param objectMapper
     * @param namespaces
     * @param quayToken
     * @return the list of containers
     */
    private static List<Container> getQuayContainers(HttpClient client, ObjectMapper objectMapper, List<String> namespaces, Token quayToken) {
        List<Container> containerList = new ArrayList<>(0);

        for (String namespace : namespaces) {
            String url = QUAY_URL + "repository?namespace=" + namespace;
            Optional<String> asString = ResourceUtilities.asString(url, quayToken.getContent(), client);

            if (asString.isPresent()) {
                Helper.RepoList repos;
                try {
                    repos = objectMapper.readValue(asString.get(), Helper.RepoList.class);
                    LOG.info("RESOURCE CALL: " + url);

                    List<Container> containers = repos.getRepositories();
                    containerList.addAll(containers);
                } catch (IOException ex) {
                    LOG.info("Exception: " + ex);
                }
            }
        }

        return containerList;
    }

    /**
     * Get the list of namespaces and organization that the user is associated to on Quay.io.
     * 
     * @param client
     * @param quayToken
     * @return list of namespaces
     */
    private static List<String> getNamespaces(HttpClient client, Token quayToken) {
        List<String> namespaces = new ArrayList<>();

        String url = QUAY_URL + "user/";
        Optional<String> asString = ResourceUtilities.asString(url, quayToken.getContent(), client);
        if (asString.isPresent()) {
            String response = asString.get();
            LOG.info("RESOURCE CALL: " + url);
            Gson gson = new Gson();

            Map<String, ArrayList> map = new HashMap<>();
            map = (Map<String, ArrayList>) gson.fromJson(response, map.getClass());
            List organizations = map.get("organizations");

            for (int i = 0; i < organizations.size(); i++) {
                Map<String, String> map2 = new HashMap<>();
                map2 = (Map<String, String>) organizations.get(i);
                LOG.info("Organization: " + map2.get("name"));
                namespaces.add(map2.get("name"));
            }
        }

        namespaces.add(quayToken.getUsername());
        return namespaces;
    }

    /**
     * Get the list of tags for each container from Quay.io.
     * 
     * @param client
     * @param containers
     * @param objectMapper
     * @param quayToken
     * @param bitbucketToken
     * @param githubRepositoryService
     * @param githubContentsService
     * @param mapOfBuilds
     * @return a map: key = path; value = list of tags
     */
    @SuppressWarnings("checkstyle:parameternumber")
    private static Map<String, List<Tag>> getTags(HttpClient client, List<Container> containers, ObjectMapper objectMapper,
            Token quayToken, Token bitbucketToken, RepositoryService githubRepositoryService, ContentsService githubContentsService,
            Map<String, ArrayList> mapOfBuilds) {
        Map<String, List<Tag>> tagMap = new HashMap<>();

        for (Container c : containers) {
            LOG.info("======================= Getting tags for: " + c.getPath() + "================================");
            String repo = c.getNamespace() + "/" + c.getName();
            String repoUrl = QUAY_URL + "repository/" + repo;
            Optional<String> asStringBuilds = ResourceUtilities.asString(repoUrl, quayToken.getContent(), client);

            List<Tag> tags = new ArrayList<>();

            if (asStringBuilds.isPresent()) {
                String json = asStringBuilds.get();
                // LOG.info(json);

                Gson gson = new Gson();
                Map<String, Map<String, Map<String, String>>> map = new HashMap<>();
                map = (Map<String, Map<String, Map<String, String>>>) gson.fromJson(json, map.getClass());

                Map<String, Map<String, String>> listOfTags = map.get("tags");

                for (String key : listOfTags.keySet()) {
                    String s = gson.toJson(listOfTags.get(key));
                    try {
                        Tag tag = objectMapper.readValue(s, Tag.class);
                        tags.add(tag);
                        // LOG.info(gson.toJson(tag));
                    } catch (IOException ex) {
                        LOG.info("Exception: " + ex);
                    }
                }

            }
            List builds = mapOfBuilds.get(c.getPath());

            if (builds != null && !builds.isEmpty()) {
                for (Tag tag : tags) {
                    LOG.info("TAG: " + tag.getName());
                    String ref;

                    for (Object build : builds) {
                        Map<String, String> idMap = new HashMap<>();
                        idMap = (Map<String, String>) build;
                        String buildId = idMap.get("id");

                        LOG.info("Build ID: " + buildId);

                        Map<String, ArrayList<String>> tagsMap = new HashMap<>();
                        tagsMap = (Map<String, ArrayList<String>>) build;

                        List<String> buildTags = tagsMap.get("tags");

                        if (buildTags.contains(tag.getName())) {
                            LOG.info("Build found with tag: " + tag.getName());

                            Map<String, Map<String, String>> triggerMetadataMap = new HashMap<>();
                            triggerMetadataMap = (Map<String, Map<String, String>>) build;

                            ref = triggerMetadataMap.get("trigger_metadata").get("ref");
                            LOG.info("REFERENCE: " + ref);
                            tag.setReference(ref);

                            if (ref == null) {
                                tag.setAutomated(false);
                            } else {
                                tag.setAutomated(true);
                            }

                            FileResponse cwlResponse = readGitRepositoryFile(c, DOCKSTORE_CWL, client, tag, githubRepositoryService,
                                    githubContentsService, bitbucketToken);
                            if (cwlResponse != null) {
                                SourceFile dockstoreCwl = new SourceFile();
                                dockstoreCwl.setType(SourceFile.FileType.DOCKSTORE_CWL);
                                dockstoreCwl.setContent(cwlResponse.getContent());
                                tag.addSourceFile(dockstoreCwl);
                            }

                            FileResponse dockerfileResponse = readGitRepositoryFile(c, DOCKERFILE, client, tag, githubRepositoryService,
                                    githubContentsService, bitbucketToken);
                            if (dockerfileResponse != null) {
                                SourceFile dockerfile = new SourceFile();
                                dockerfile.setType(SourceFile.FileType.DOCKERFILE);
                                dockerfile.setContent(dockerfileResponse.getContent());
                                tag.addSourceFile(dockerfile);
                            }

                            break;
                        }
                    }
                }
            }

            tagMap.put(c.getPath(), tags);
        }

        return tagMap;
    }

    /**
     * Refreshes user's containers
     * 
     * @param userId
     * @param client
     * @param objectMapper
     * @param userDAO
     * @param containerDAO
     * @param tokenDAO
     * @param tagDAO
     * @param fileDAO
     * @return list of updated containers
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public static List<Container> refresh(Long userId, HttpClient client, ObjectMapper objectMapper, UserDAO userDAO,
            ContainerDAO containerDAO, TokenDAO tokenDAO, TagDAO tagDAO, FileDAO fileDAO) {
        User dockstoreUser = userDAO.findById(userId);

        List<Container> currentRepos = new ArrayList(dockstoreUser.getContainers());// containerDAO.findByUserId(userId);
        List<Token> tokens = tokenDAO.findByUserId(userId);

        SimpleDateFormat formatter = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z");

        List<String> namespaces = new ArrayList<>();

        Token quayToken = null;
        Token githubToken = null;
        Token bitbucketToken = null;

        // Get user's quay and git tokens
        for (Token token : tokens) {
            if (token.getTokenSource().equals(TokenType.QUAY_IO.toString())) {
                quayToken = token;
            }
            if (token.getTokenSource().equals(TokenType.GITHUB_COM.toString())) {
                githubToken = token;
            }
            if (token.getTokenSource().equals(TokenType.BITBUCKET_ORG.toString())) {
                bitbucketToken = token;
            }
        }

        if (githubToken == null || quayToken == null) {
            LOG.info("GIT or QUAY token not found!");
            throw new WebApplicationException(HttpStatus.SC_CONFLICT);
        }
        if (bitbucketToken == null) {
            LOG.info("WARNING: BITBUCKET token not found!");
        }

        namespaces.addAll(getNamespaces(client, quayToken));

        GitHubClient githubClient = new GitHubClient();
        githubClient.setOAuth2Token(githubToken.getContent());

        RepositoryService service = new RepositoryService(githubClient);
        ContentsService cService = new ContentsService(githubClient);

        List<Container> allRepos = getQuayContainers(client, objectMapper, namespaces, quayToken);

        Map<String, ArrayList> mapOfBuilds = new HashMap<>();

        // Go through each container for each namespace
        for (Container c : allRepos) {
            String repo = c.getNamespace() + "/" + c.getName();
            String path = quayToken.getTokenSource() + "/" + repo;
            c.setPath(path);

            LOG.info("========== Configuring " + path + " ==========");

            // Get the list of builds from the container.
            // Builds contain information such as the Git URL and tags
            String urlBuilds = QUAY_URL + "repository/" + repo + "/build/";
            Optional<String> asStringBuilds = ResourceUtilities.asString(urlBuilds, quayToken.getContent(), client);

            String gitURL = "";

            if (asStringBuilds.isPresent()) {
                String json = asStringBuilds.get();
                LOG.info("RESOURCE CALL: " + urlBuilds);

                // parse json using Gson to get the git url of repository and the list of tags
                Gson gson = new Gson();
                Map<String, ArrayList> map = new HashMap<>();
                map = (Map<String, ArrayList>) gson.fromJson(json, map.getClass());
                ArrayList builds = map.get("builds");

                mapOfBuilds.put(path, builds);

                if (!builds.isEmpty()) {
                    Map<String, Map<String, String>> map2 = new HashMap<>();
                    map2 = (Map<String, Map<String, String>>) builds.get(0);

                    gitURL = map2.get("trigger_metadata").get("git_url");

                    Map<String, String> map3 = (Map<String, String>) builds.get(0);
                    String lastBuild = map3.get("started");
                    LOG.info("LAST BUILD: " + lastBuild);

                    Date date = null;
                    try {
                        date = formatter.parse(lastBuild);
                        c.setLastBuild(date);
                    } catch (ParseException ex) {
                        LOG.info("Build date did not match format 'EEE, d MMM yyyy HH:mm:ss Z'");
                    }
                }
            }

            c.setRegistry(quayToken.getTokenSource());
            c.setGitUrl(gitURL);

            final SourceCodeRepoInterface sourceCodeRepo = SourceCodeRepoFactory.createSourceCodeRepo(service, cService, c.getGitUrl(),
                    client, bitbucketToken == null ? null : bitbucketToken.getContent());
            if (sourceCodeRepo != null) {
                // find if there is a Dockstore.cwl file from the git repository
                sourceCodeRepo.findCWL(c);
            }
        }

        Map<String, List<Tag>> tagMap = getTags(client, allRepos, objectMapper, quayToken, bitbucketToken, service, cService, mapOfBuilds);

        currentRepos = Helper.updateContainers(allRepos, currentRepos, dockstoreUser, containerDAO, tagDAO, fileDAO, tagMap);
        userDAO.clearCache();
        return new ArrayList(userDAO.findById(userId).getContainers());
    }

    /**
     * Read a file from the container's git repository.
     * 
     * @param container
     * @param fileName
     * @param client
     * @param tag
     * @param githubRepositoryservice
     * @param githubContentsService
     * @param bitbucketToken
     * @return a FileResponse instance
     */
    public static FileResponse readGitRepositoryFile(Container container, String fileName, HttpClient client, Tag tag,
            RepositoryService githubRepositoryservice, ContentsService githubContentsService, Token bitbucketToken) {
        String bitbucketTokenContent = (bitbucketToken == null) ? null : bitbucketToken.getContent();

        if (container.getGitUrl() == null || container.getGitUrl().isEmpty()) {
            return null;
        }
        final SourceCodeRepoInterface sourceCodeRepo = SourceCodeRepoFactory.createSourceCodeRepo(githubRepositoryservice,
                githubContentsService, container.getGitUrl(), client, bitbucketTokenContent);

        if (sourceCodeRepo == null) {
            return null;
        }

        final String reference = sourceCodeRepo.getReference(container.getGitUrl(), tag.getReference());

        return sourceCodeRepo.readFile(fileName, reference);
    }

    /**
     * Refreshes user's Bitbucket token.
     * 
     * @param token
     * @param client
     * @param tokenDAO
     * @param bitbucketClientID
     * @param bitbucketClientSecret
     * @return the updated token
     */
    public static Token refreshBitbucketToken(Token token, HttpClient client, TokenDAO tokenDAO, String bitbucketClientID,
            String bitbucketClientSecret) {

        String url = BITBUCKET_URL + "site/oauth2/access_token";

        try {
            Optional<String> asString = ResourceUtilities.bitbucketPost(url, null, client, bitbucketClientID, bitbucketClientSecret,
                    "grant_type=refresh_token&refresh_token=" + token.getRefreshToken());

            String accessToken;
            String refreshToken;
            if (asString.isPresent()) {
                LOG.info("RESOURCE CALL: " + url);
                String json = asString.get();

                Gson gson = new Gson();
                Map<String, String> map = new HashMap<>();
                map = (Map<String, String>) gson.fromJson(json, map.getClass());

                accessToken = map.get("access_token");
                refreshToken = map.get("refresh_token");

                token.setContent(accessToken);
                token.setRefreshToken(refreshToken);

                long create = tokenDAO.create(token);
                return tokenDAO.findById(create);
            } else {
                throw new WebApplicationException("Could not retrieve bitbucket.org token based on code");
            }
        } catch (UnsupportedEncodingException ex) {
            LOG.info(ex.toString());
            throw new WebApplicationException(HttpStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Check if admin
     * 
     * @param user
     */
    public static void checkUser(User user) {
        if (!user.getIsAdmin()) {
            throw new WebApplicationException(HttpStatus.SC_FORBIDDEN);
        }
    }

    /**
     * Check if admin or correct user
     * 
     * @param user
     * @param id
     */
    public static void checkUser(User user, long id) {
        if (!user.getIsAdmin() && user.getId() != id) {
            throw new WebApplicationException(HttpStatus.SC_FORBIDDEN);
        }
    }

    /**
     * Check if admin or if container belongs to user
     * 
     * @param user
     * @param container
     */
    public static void checkUser(User user, Container container) {
        if (!user.getIsAdmin() && !container.getUsers().contains(user)) {
            throw new WebApplicationException(HttpStatus.SC_FORBIDDEN);
        }
    }

    /**
     * Check if container is null
     * 
     * @param container
     */
    public static void checkContainer(Container container) {
        if (container == null) {
            throw new WebApplicationException(HttpStatus.SC_BAD_REQUEST);
        }
    }
}
