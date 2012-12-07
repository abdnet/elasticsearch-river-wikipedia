This fork works the same way as the [original plugin](https://github.com/elasticsearch/elasticsearch-river-wikipedia) but adds the following data of the WikiPage:
- timestamp
- username

It also possible to limit the number of links to index, as well as excluding some data:


curl -XPOST "localhost:9200/_river/my_river/_meta" -d "{
	"type": "wikipedia",
	"wikipedia": {
		"url": "....",
        "max_links": 3,
		"excludes": ["special", "stub", "disambiguation", "category", "redirect", "link"]
	},
	"index": {
	    "index": "my_river",
        "type": "page",
        "bulk_size": 500
	}
}"



License
-------

    This software is licensed under the Apache 2 license, quoted below.

    Copyright 2009-2012 Shay Banon and ElasticSearch <http://www.elasticsearch.org>

    Licensed under the Apache License, Version 2.0 (the "License"); you may not
    use this file except in compliance with the License. You may obtain a copy of
    the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
    License for the specific language governing permissions and limitations under
    the License.
