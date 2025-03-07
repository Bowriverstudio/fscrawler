/*
 * Licensed to David Pilato (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package fr.pilato.elasticsearch.crawler.fs.rest;

import static fr.pilato.elasticsearch.crawler.fs.client.ElasticsearchClientUtil.decodeCloudId;
import static fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerUtil.localDateTimeToDate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

import org.apache.commons.io.FilenameUtils;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;

import com.fasterxml.jackson.databind.JsonNode;

import fr.pilato.elasticsearch.crawler.fs.beans.Doc;
import fr.pilato.elasticsearch.crawler.fs.beans.DocParser;
import fr.pilato.elasticsearch.crawler.fs.client.ElasticsearchClient;
import fr.pilato.elasticsearch.crawler.fs.framework.FsCrawlerUtil;
import fr.pilato.elasticsearch.crawler.fs.framework.MetaParser;
import fr.pilato.elasticsearch.crawler.fs.framework.SignTool;
import fr.pilato.elasticsearch.crawler.fs.settings.Elasticsearch;
import fr.pilato.elasticsearch.crawler.fs.settings.FsSettings;
import fr.pilato.elasticsearch.crawler.fs.tika.TikaDocParser;

@Path("/_upload")
public class UploadApi extends RestApi {

    private final ElasticsearchClient esClient;
    private final FsSettings settings;
    private final MessageDigest messageDigest;
    private static final TimeBasedUUIDGenerator TIME_UUID_GENERATOR = new TimeBasedUUIDGenerator();

    UploadApi(FsSettings settings, ElasticsearchClient esClient) {
        this.settings = settings;
        this.esClient = esClient;
        // Create MessageDigest instance
        try {
            messageDigest = settings.getFs().getChecksum() == null ?
                    null : MessageDigest.getInstance(settings.getFs().getChecksum());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("This should never happen as we checked that previously");
        }
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public UploadResponse post(
            @QueryParam("debug") String debug,
            @QueryParam("simulate") String simulate,
            @FormDataParam("id") String id,
            @FormDataParam("tags") InputStream tags,
            @FormDataParam("file") InputStream filecontent,
            @FormDataParam("file") FormDataContentDisposition d) throws IOException, NoSuchAlgorithmException {

        // Create the Doc object
        Doc doc = new Doc();

        String filename = new String(d.getFileName().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        long filesize = d.getSize();

        // File
        doc.getFile().setFilename(filename);
        doc.getFile().setExtension(FilenameUtils.getExtension(filename).toLowerCase());
        doc.getFile().setIndexingDate(localDateTimeToDate(LocalDateTime.now()));
        // File

        // Path
        if (id == null) {
            id = SignTool.sign(filename);
        } else if (id.equals("_auto_")) {
            // We are using a specific id which tells us to generate a unique _id like elasticsearch does
            id = TIME_UUID_GENERATOR.getBase64UUID();
        }

        doc.getPath().setVirtual(filename);
        doc.getPath().setReal(filename);
        // Path

        // Read the file content
        TikaDocParser.generate(settings, filecontent, filename, doc, messageDigest, filesize);

        String url = null;
        if (Boolean.parseBoolean(simulate)) {
            logger.debug("Simulate mode is on, so we skip sending document [{}] to elasticsearch.", filename);
        } else {
            logger.debug("Sending document [{}] to elasticsearch.", filename);
            doc = this.getMergedJsonDoc(doc, tags);
            esClient.index(
                    settings.getElasticsearch().getIndex(),
                    esClient.getDefaultTypeName(),
                    id,
                    DocParser.toJson(doc),
                    settings.getElasticsearch().getPipeline());
            // Elasticsearch entity coordinates (we use the first node address)
            Elasticsearch.Node node = settings.getElasticsearch().getNodes().get(0);
            String nodeUrl;
            if (node.getCloudId() != null) {
                nodeUrl = decodeCloudId(node.getCloudId());
            } else {
                nodeUrl = node.getUrl();
            }
            url = nodeUrl + "/" +
                    settings.getElasticsearch().getIndex() + "/" +
                    esClient.getDefaultTypeName() + "/" +
                    id;
        }

        UploadResponse response = new UploadResponse();
        response.setOk(true);
        response.setFilename(filename);
        response.setUrl(url);

        if (logger.isDebugEnabled() || Boolean.parseBoolean(debug)) {
            // We send the content back if debug is on or if we got in the query explicitly a debug command
            response.setDoc(doc);
        }

        return response;
    }

    private Doc getMergedJsonDoc(Doc doc, InputStream tags) throws BadRequestException {
        if (tags == null) {
            return doc;
        }

        try {
            JsonNode tagsNode = MetaParser.mapper.readTree(tags);
            JsonNode docNode = MetaParser.mapper.convertValue(doc, JsonNode.class);

            JsonNode mergedNode = FsCrawlerUtil.merge(tagsNode, docNode);

            Doc mergedDoc = MetaParser.mapper.treeToValue(mergedNode, Doc.class);
            return mergedDoc;
        } catch (Exception e) {
            logger.error("Error parsing tags", e);
            throw new BadRequestException("Error parsing tags", e);
        }
    }

}
