package com.xiaomi.mone.log.manager.domain;

import com.xiaomi.mone.log.manager.model.vo.LogContextQuery;
import com.xiaomi.mone.log.manager.model.vo.LogQuery;
import com.xiaomi.youpin.docean.anno.Service;
import com.xiaomi.youpin.docean.common.DoceanConfig;
import com.xiaomi.youpin.docean.common.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class SearchLog {
    /**
     * 获取查询参数
     * @param logQuery
     * @param keyList
     * @return
     */
    public BoolQueryBuilder getQueryBuilder(LogQuery logQuery, List<String> keyList) throws Exception {
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        boolQueryBuilder.filter(QueryBuilders.rangeQuery("timestamp").from(logQuery.getStartTime()).to(logQuery.getEndTime()));
        boolQueryBuilder.filter(QueryBuilders.termQuery("logstore", logQuery.getLogstore()));
        // 支持tail多选
        if (StringUtils.isNotEmpty(logQuery.getTail())) {
            BoolQueryBuilder tailQueryBuilder = QueryBuilders.boolQuery();
            String[] tailLimitArray = logQuery.getTail().split(",");
            for (String tail : tailLimitArray) {
                tailQueryBuilder.should(QueryBuilders.termQuery("tail", tail));
            }
            tailQueryBuilder.minimumShouldMatch(1);
            boolQueryBuilder.filter(tailQueryBuilder);
        }
        if (StringUtils.isEmpty(logQuery.getFullTextSearch())) {
            return boolQueryBuilder;
        }
        BoolQueryBuilder fullTextSearchBuilder = buildTextQuery(logQuery.getFullTextSearch(), keyList);
        if (fullTextSearchBuilder != null) {
            boolQueryBuilder.filter(fullTextSearchBuilder);
        }
        return boolQueryBuilder;
    }

    private static BoolQueryBuilder buildTextQuery(String querytext, List<String> keyList) {
        List<String> mustQueryTextList = new ArrayList<>();
        List<String> mustNotQueryTextList = new ArrayList<>();
        queryAnalyse(querytext, mustQueryTextList, mustNotQueryTextList);
        BoolQueryBuilder boolQueryBuilder = queryDispatchAndBuild(mustQueryTextList, mustNotQueryTextList, keyList);
        return boolQueryBuilder;
    }

    private static BoolQueryBuilder queryDispatchAndBuild(List<String> mustQueryTextList, List<String> mustNotQueryTextList, List<String> keyList) {
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
        QueryBuilder thisQueryBuilder;
        boolean isGrantQuery;
        String queryText;
        int i = 0;
        while(i < mustQueryTextList.size() + mustNotQueryTextList.size()) {
            isGrantQuery = i < mustQueryTextList.size();
            queryText = isGrantQuery ? mustQueryTextList.get(i) : mustNotQueryTextList.get(i - mustQueryTextList.size());
            i++;
            if (queryText.startsWith("\"")) {
                queryText = queryText.substring(1, queryText.length() - 1);
                thisQueryBuilder = precisionQueryBuilder(queryText);
                queryBuilder = isGrantQuery ? queryBuilder.must(thisQueryBuilder) : queryBuilder.mustNot(thisQueryBuilder);
                continue;
            }
            if (queryText.contains(":")) {
                int kvApartIndex = queryText.indexOf(":");
                String key = queryText.substring(0, kvApartIndex);
                String value = kvApartIndex == queryText.length() ? "" : queryText.substring(kvApartIndex + 1);
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                    thisQueryBuilder = kvPrecisionQueryBuilder(key, value);
                    queryBuilder = isGrantQuery ? queryBuilder.must(thisQueryBuilder) : queryBuilder.mustNot(thisQueryBuilder);
                    continue;
                } else {
                    thisQueryBuilder = kvMatchQueryBuilder(key, value);
                    queryBuilder = isGrantQuery ? queryBuilder.must(thisQueryBuilder) : queryBuilder.mustNot(thisQueryBuilder);
                    continue;
                }
            }
            thisQueryBuilder = multiMatchQueryBuilder(queryText, keyList);
            queryBuilder = isGrantQuery ? queryBuilder.must(thisQueryBuilder) : queryBuilder.mustNot(thisQueryBuilder);
        }
        return queryBuilder;
    }

    private static BoolQueryBuilder queryAnalyse(String querytext, List<String> mustQueryTextList, List<String> mustNotQueryTextList) {
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        boolean isGrantQuery; // 判断搜索词是否加了not
        do {
            isGrantQuery = true;
            // 如果搜索词加了not 那not后的所有搜索条件都是not的逻辑
            if (isGrantQuery && (querytext.toLowerCase().startsWith("not "))) {
                isGrantQuery = false;
                // 搜索语句去掉”not “
                querytext = querytext.substring(4);
            }
            int endIndex = getEndIndex(querytext);
            String thisQuerytext = querytext.substring(0, endIndex);
            if (isGrantQuery) {
                mustQueryTextList.add(thisQuerytext);
            } else {
                mustNotQueryTextList.add(thisQuerytext);
            }
            querytext = querytext.substring(endIndex);
            // 搜索语句去掉” and “
            if (querytext.toLowerCase().startsWith(" and ")) {
                querytext = querytext.substring(5);
            }
        } while (StringUtils.isNotEmpty(querytext));

        return boolQueryBuilder;
    }

    private static QueryBuilder kvMatchQueryBuilder(String key, String value) {
        if ("logLevel".equals(key) || "level".equals(key) && ("INFO".equalsIgnoreCase(value) || "WARN".equalsIgnoreCase(value))) {
            value = String.format("%-5s", value);
        }
        return QueryBuilders.matchQuery(key, value);
    }

    private static QueryBuilder multiMatchQueryBuilder(String querytext, List<String> keyList) {
        BoolQueryBuilder textQueryBuilder = QueryBuilders.boolQuery();
        // 全部加上正则表达式查询
        textQueryBuilder.should(regexpQuery(querytext));
        QueryBuilder queryBuilder = QueryBuildChain.doChain(querytext, keyList);
        textQueryBuilder.should(queryBuilder);
        textQueryBuilder.minimumShouldMatch(1);
        return textQueryBuilder;
    }

    // 正则表达式查询
    private static QueryBuilder regexpQuery(String querytext) {
        return QueryBuilders.regexpQuery("message", querytext);
    }

    // 精准+前缀查询
    private static QueryBuilder precisionQueryBuilder(String querytext) {
        BoolQueryBuilder phraseQueryBuilder = QueryBuilders.boolQuery();
        phraseQueryBuilder.should(QueryBuilders.matchPhrasePrefixQuery("message", querytext));
        phraseQueryBuilder.should(QueryBuilders.termQuery("traceId", querytext));
        phraseQueryBuilder.minimumShouldMatch(1);
        return phraseQueryBuilder;
    }

    // kv精确+前缀查询
    private static QueryBuilder kvPrecisionQueryBuilder(String key, String value) {
        return QueryBuilders.matchPhrasePrefixQuery(key, value);
    }

    public static class QueryBuildChain {
        private static final String[] simpleQueryStringSymble = {"+", "|", "-"};
        private static final String[] simpleQueryStringEndSymble = {"*"};
        private static final String[] wildcardQuerySymble = {"*", "?"};

        private static QueryBuilder doChain(String querytext, List<String> keyList) {
            return simpleQueryString(querytext, keyList);
        }

        // 简单语句查询
        private static QueryBuilder simpleQueryString(String querytext, List<String> keyList) {
            for (String symble : simpleQueryStringSymble) {
                if (querytext.contains(symble)) {
                    return QueryBuilders.simpleQueryStringQuery(querytext);
                }
            }
            for (String symble : simpleQueryStringEndSymble) {
                if (querytext.endsWith(symble)) {
                    return QueryBuilders.simpleQueryStringQuery(querytext);
                }
            }
            return wildcardQuery(querytext, keyList);
        }

        // 模糊查询
        private static QueryBuilder wildcardQuery(String querytext, List<String> keyList) {
            for (String symble : wildcardQuerySymble) {
                if (querytext.contains(symble)) {
                    return QueryBuilders.wildcardQuery("message", querytext);
                }
            }
            return multiMatchQuery(querytext, keyList);
        }

        // 多字段查询
        private static QueryBuilder multiMatchQuery(String querytext, List<String> keyList) {
            return QueryBuilders.multiMatchQuery(querytext, keyList.toArray(new String[keyList.size()]));
        }
    }

    private static int getEndIndex(String querytext) {
        querytext = querytext.toLowerCase();
        // 短语查询 返回第二个引号的坐标
        if (querytext.startsWith("\"")) {
            return querytext.substring(1).indexOf("\"") + 2;
        }
        // 多条件查询
        int endIndex = querytext.indexOf(" and ");
        if (endIndex == -1 || querytext.substring(0, endIndex).contains("not ")) {
            endIndex = querytext.indexOf("not ");
        }
        if (endIndex == -1) {
            return querytext.length(); // 单条件查询
        }
        // k-v查询的v包含”and“,例如：a:"1 and b"
        String thisQueryText = querytext.substring(0, endIndex);
        if (thisQueryText.contains(":")) {
            String queryValue = querytext.substring(querytext.indexOf(":") + 1);
            if (queryValue.startsWith("\"")) {
                endIndex = queryValue.substring(1).indexOf("\"") + 2 + thisQueryText.indexOf(":") + 1;
            }
        }
        return endIndex;
    }

    public static void main(String[] args) throws Exception {
        String s = "\"aa\"";
        String s1 = "\"aa\" And \"bb5\"";
        String s2 = "a:1 and b:2 and c:3";
        String s3 = "aa:\"11 and 22\"";
        String s4 = "a:1 and b:2 Not c:3 AND d:4";
        String s5 = "NOT a:1";
        String s6 = "a:1:3";
        String s7 = "";

        List<String> keyList = new ArrayList<>();
        keyList.add("a");
        keyList.add("b");
        BoolQueryBuilder sqb = buildTextQuery(s, keyList);
        BoolQueryBuilder s1qb = buildTextQuery(s1, keyList);
        BoolQueryBuilder s2qb = buildTextQuery(s2, keyList);
        BoolQueryBuilder s3qb = buildTextQuery(s3, keyList);
        BoolQueryBuilder s4qb = buildTextQuery(s4, keyList);
        BoolQueryBuilder s5qb = buildTextQuery(s5, keyList);
        BoolQueryBuilder s6qb = buildTextQuery(s6, keyList);
        BoolQueryBuilder s7qb = buildTextQuery(s7, keyList);
        System.out.println("end");
    }

    public boolean isLegalParam(LogContextQuery param) {
        if (param == null
                || StringUtils.isEmpty(param.getLogstore())
                || StringUtils.isEmpty(param.getIp())
                || StringUtils.isEmpty(param.getFileName())
                || param.getLineNumber() == null
                || StringUtils.isEmpty(param.getTimestamp())
                || param.getType() == null
                || param.getPageSize() == null) {
            return false;
        }
        return true;
    }

    public void downLogFile(HSSFWorkbook excel, String fileName) throws IOException {
        File file = null;
        FileOutputStream fos = null;
        try {
            file = new File(DoceanConfig.ins().get("download_file_path", "/tmp") + File.separator + fileName);
            file.createNewFile();
            fos = new FileOutputStream(file);
            excel.write(fos);
            Down.down(fileName);
        } finally {
            if (excel != null) {
                excel.close();
            }
            if (fos != null) {
                fos.close();
            }
            if (file != null) {
                file.delete();
            }
        }
    }

    public String esHistogramInterval(Long duration) {
        duration = duration / 1000;
        if (duration > 24 * 60 * 60) {
            duration = duration / 100;
            return duration + "s";
        } else if (duration > 12 * 60 * 60) {
            duration = duration / 80;
            return duration + "s";
        } else if (duration > 6 * 60 * 60) {
            duration = duration / 60;
            return duration + "s";
        } else if (duration > 60 * 60) {
            duration = duration / 50;
            return duration + "s";
        } else if (duration > 30 * 60) {
            duration = duration / 40;
            return duration + "s";
        } else if (duration > 10 * 60) {
            duration = duration / 30;
            return duration + "s";
        } else if (duration > 5 * 60) {
            duration = duration / 25;
            return duration + "s";
        } else if (duration > 3 * 60) {
            duration = duration / 20;
            return duration + "s";
        } else if (duration > 60) {
            duration = duration / 15;
            return duration + "s";
        } else if (duration > 10) {
            duration = duration / 10;
            return duration + "s";
        } else {
            return "";
        }
    }
}
