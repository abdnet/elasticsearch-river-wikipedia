/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.river.wikipedia;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;
import org.elasticsearch.river.wikipedia.support.PageCallbackHandler;
import org.elasticsearch.river.wikipedia.support.WikiPage;
import org.elasticsearch.river.wikipedia.support.WikiXMLParser;
import org.elasticsearch.river.wikipedia.support.WikiXMLParserFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 */
public class WikipediaRiver extends AbstractRiverComponent implements River {

    private StringBuilder sb = new StringBuilder();

    private final Client client;

    private final URL url;

    private final String indexName;

    private final String typeName;

    private final int bulkSize;

    private final int dropThreshold;

    private final int maxLinks;

    private final Set<String> excludes;


    private final AtomicInteger onGoingBulks = new AtomicInteger();

    private volatile Thread thread;

    private volatile boolean closed = false;

    private volatile BulkRequestBuilder currentRequest;

    @SuppressWarnings({"unchecked"})
    @Inject
    public WikipediaRiver(RiverName riverName, RiverSettings settings, Client client) throws MalformedURLException {
        super(riverName, settings);
        this.client = client;

        String url = "http://download.wikimedia.org/enwiki/latest/enwiki-latest-pages-articles.xml.bz2";
        int maxLinks = 5;
        excludes = new HashSet<String>();
        if (settings.settings().containsKey("wikipedia")) {
            Map<String, Object> wikipediaSettings = (Map<String, Object>) settings.settings().get("wikipedia");
            url = XContentMapValues.nodeStringValue(wikipediaSettings.get("url"), url);
            maxLinks = XContentMapValues.nodeIntegerValue(wikipediaSettings.get("max_links"), 5);
            if (XContentMapValues.isArray(wikipediaSettings.get("excludes"))) {
                for (Object exclude : (List) wikipediaSettings.get("excludes")) {
                    if (exclude instanceof String) {
                        excludes.add((String) exclude);
                    }
                }
            }
        }

        logger.info("creating wikipedia stream river for [{}]", url);
        this.url = new URL(url);
        this.maxLinks = maxLinks;

        if (settings.settings().containsKey("index")) {
            Map<String, Object> indexSettings = (Map<String, Object>) settings.settings().get("index");
            indexName = XContentMapValues.nodeStringValue(indexSettings.get("index"), riverName.name());
            typeName = XContentMapValues.nodeStringValue(indexSettings.get("type"), "status");
            this.bulkSize = XContentMapValues.nodeIntegerValue(indexSettings.get("bulk_size"), 100);
            this.dropThreshold = XContentMapValues.nodeIntegerValue(indexSettings.get("drop_threshold"), 10);
        } else {
            indexName = riverName.name();
            typeName = "page";
            bulkSize = 100;
            dropThreshold = 10;
        }
    }

    @Override
    public void start() {
        logger.info("starting wikipedia stream");
        try {
            client.admin().indices().prepareCreate(indexName).execute().actionGet();
        } catch (Exception e) {
            if (ExceptionsHelper.unwrapCause(e) instanceof IndexAlreadyExistsException) {
                // that's fine
            } else if (ExceptionsHelper.unwrapCause(e) instanceof ClusterBlockException) {
                // ok, not recovered yet..., lets start indexing and hope we recover by the first bulk
                // TODO: a smarter logic can be to register for cluster event listener here, and only start sampling when the block is removed...
            } else {
                logger.warn("failed to create index [{}], disabling river...", e, indexName);
                return;
            }
        }
        currentRequest = client.prepareBulk();
        WikiXMLParser parser = WikiXMLParserFactory.getSAXParser(url);
        try {
            parser.setPageCallback(new PageCallback(this.excludes, this.maxLinks));
        } catch (Exception e) {
            logger.error("failed to create parser", e);
            return;
        }
        thread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "wikipedia_slurper").newThread(new Parser(parser));
        thread.start();
    }

    @Override
    public void close() {
        logger.info("closing wikipedia river");
        closed = true;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private class Parser implements Runnable {
        private final WikiXMLParser parser;

        private Parser(WikiXMLParser parser) {
            this.parser = parser;
        }

        @Override
        public void run() {
            try {
                parser.parse();
            } catch (Exception e) {
                if (closed) {
                    return;
                }
                logger.error("failed to parse stream", e);
            }
        }
    }

    private class PageCallback implements PageCallbackHandler {

        boolean ignoreTitle = false;
        boolean ignoreTimestamp = false;
        boolean ignoreUsername = false;
        boolean ignoreRedirect = false;
        boolean ignoreSpecial = false;
        boolean ignoreStub = false;
        boolean ignoreText = false;
        boolean ignoreDisambiguation = false;
        boolean ignoreCategory = false;
        boolean ignoreLink = false;
        Integer maxLinks = 100;

        private PageCallback() {
        }

        private PageCallback(Set<String> excludes, Integer maxLinks) {
            if (excludes != null) {
                for (String exclude : excludes) {
                    if ("title".equalsIgnoreCase(exclude)) {
                        ignoreTitle = true;
                    } else if ("timestamp".equalsIgnoreCase(exclude)) {
                        ignoreTimestamp = true;
                    } else if ("username".equalsIgnoreCase(exclude)) {
                        ignoreUsername = true;
                    } else if ("text".equalsIgnoreCase(exclude)) {
                        ignoreText = true;
                    } else if ("redirect".equalsIgnoreCase(exclude)) {
                        ignoreRedirect = true;
                    } else if ("special".equalsIgnoreCase(exclude)) {
                        ignoreSpecial = true;
                    } else if ("stub".equalsIgnoreCase(exclude)) {
                        ignoreStub = true;
                    } else if ("disambiguation".equalsIgnoreCase(exclude)) {
                        ignoreDisambiguation = true;
                    } else if ("category".equalsIgnoreCase(exclude)) {
                        ignoreCategory = true;
                    } else if ("link".equalsIgnoreCase(exclude)) {
                        ignoreLink = true;
                    }
                }
            }
            if (maxLinks != null) {
                this.maxLinks = maxLinks;
            }
        }

        @Override
        public void process(WikiPage page) {
            if (closed) {
                return;
            }
            String title = stripTitle(page.getTitle());
            if (logger.isTraceEnabled()) {
                logger.trace("page {} : {}", page.getID(), page.getTitle());
            }
            try {
                XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
                if (!ignoreTitle) {
                    builder.field("title", title);
                }
                if (!ignoreTimestamp) {
                    builder.field("timestamp", page.getTimestamp());
                }
                if (!ignoreUsername) {
                    builder.field("username", page.getUsername());
                }

                if (!ignoreText) {
                    builder.field("text", page.getText());
                }

                if (!ignoreRedirect) {
                    builder.field("redirect", page.isRedirect());
                }

                if (!ignoreSpecial) {
                    builder.field("special", page.isSpecialPage());
                }

                if (!ignoreStub) {
                    builder.field("stub", page.isStub());
                }

                if (!ignoreDisambiguation) {
                    builder.field("disambiguation", page.isDisambiguationPage());
                }

                if (!ignoreCategory) {
                    builder.startArray("category");
                    for (String s : page.getCategories()) {
                        builder.value(s);
                    }
                    builder.endArray();
                }


                if (!ignoreLink) {
                    int count = 1;
                    builder.startArray("link");
                    for (String s : page.getLinks()) {
                        if ((maxLinks != null) && (count > maxLinks)) {
                            break;
                        }
                        builder.value(s);
                        count++;
                    }
                    builder.endArray();
                }

                builder.endObject();
                // For now, we index (and not create) since we need to keep track of what we indexed...
                currentRequest.add(Requests.indexRequest(indexName).type(typeName).id(page.getID()).create(false).source(builder));
                processBulkIfNeeded();
            } catch (Exception e) {
                logger.warn("failed to construct index request", e);
            }
        }

        private void processBulkIfNeeded() {
            if (currentRequest.numberOfActions() >= bulkSize) {
                // execute the bulk operation
                int currentOnGoingBulks = onGoingBulks.incrementAndGet();
                if (currentOnGoingBulks > dropThreshold) {
                    // TODO, just wait here!, we can slow down the wikipedia parsing
                    onGoingBulks.decrementAndGet();
                    logger.warn("dropping bulk, [{}] crossed threshold [{}]", onGoingBulks, dropThreshold);
                } else {
                    try {
                        currentRequest.execute(new ActionListener<BulkResponse>() {
                            @Override
                            public void onResponse(BulkResponse bulkResponse) {
                                onGoingBulks.decrementAndGet();
                            }

                            @Override
                            public void onFailure(Throwable e) {
                                logger.warn("failed to execute bulk");
                            }
                        });
                    } catch (Exception e) {
                        logger.warn("failed to process bulk", e);
                    }
                }
                currentRequest = client.prepareBulk();
            }
        }
    }


    private String stripTitle(String title) {
        sb.setLength(0);
        sb.append(title);
        while (sb.length() > 0 && (sb.charAt(sb.length() - 1) == '\n' || (sb.charAt(sb.length() - 1) == ' '))) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }
}
