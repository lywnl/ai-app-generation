package com.lyw.appgeneration.service.rag.retrieval;

import com.lyw.appgeneration.service.rag.catalog.TemplateCatalog;
import com.lyw.appgeneration.service.rag.model.KnowledgeChunk;
import com.lyw.appgeneration.service.rag.model.RagDocumentKind;
import com.lyw.appgeneration.service.rag.model.RankedCandidate;
import com.lyw.appgeneration.service.rag.model.TemplateDoc;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 基于当前 Vue 知识目录构建的本地 BM25 召回通道。
 */
public class Bm25Retriever implements AutoCloseable {

    private static final String FIELD_CHUNK_ID = "chunkId";
    private static final String FIELD_DOCUMENT_ID = "documentId";
    private static final String FIELD_DOCUMENT_KIND = "documentKind";
    private static final String FIELD_CHUNK_KIND = "chunkKind";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_EXACT = "exact";

    private static final BM25Similarity BM25_SIMILARITY = new BM25Similarity();

    private final String catalogVersion;
    private final Directory directory;
    private final Analyzer analyzer;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;

    /**
     * 从已校验目录一次性构建内存索引。
     *
     * @param catalog Vue 知识目录
     */
    public Bm25Retriever(TemplateCatalog catalog) throws IOException {
        this.catalogVersion = catalog.getCatalogVersion();
        this.directory = new ByteBuffersDirectory();
        this.analyzer = new SmartChineseAnalyzer();
        buildIndex(catalog);
        this.reader = DirectoryReader.open(directory);
        this.searcher = new IndexSearcher(reader);
        this.searcher.setSimilarity(BM25_SIMILARITY);
    }

    /**
     * 按父文档类型召回并聚合父文档候选。
     */
    public List<RankedCandidate> retrieve(String query, RagDocumentKind documentKind, int topK) {
        if (query == null || query.isBlank() || documentKind == null || topK <= 0) {
            return List.of();
        }
        try {
            Query luceneQuery = createQuery(query, documentKind);
            TopDocs topDocs = searcher.search(luceneQuery, reader.numDocs());
            return aggregate(topDocs.scoreDocs, documentKind, topK);
        } catch (IOException exception) {
            throw new IllegalStateException("Lucene BM25 召回失败", exception);
        }
    }

    public String getCatalogVersion() {
        return catalogVersion;
    }

    private void buildIndex(TemplateCatalog catalog) throws IOException {
        Map<String, TemplateDoc> documentsById = catalog.getDocuments().stream()
                .collect(Collectors.toMap(TemplateDoc::getId, Function.identity()));
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setSimilarity(BM25_SIMILARITY);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (KnowledgeChunk chunk : catalog.getChunks()) {
                writer.addDocument(createDocument(chunk, documentsById.get(chunk.documentId())));
            }
            writer.commit();
        }
    }

    private Document createDocument(KnowledgeChunk chunk, TemplateDoc parent) {
        Document document = new Document();
        document.add(new StringField(FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
        document.add(new StringField(FIELD_DOCUMENT_ID, chunk.documentId(), Field.Store.YES));
        document.add(new StringField(FIELD_DOCUMENT_KIND, chunk.documentKind().name(), Field.Store.YES));
        document.add(new StringField(FIELD_CHUNK_KIND, chunk.chunkKind().name(), Field.Store.YES));
        document.add(new TextField(FIELD_TEXT, chunk.searchText(), Field.Store.NO));
        exactTerms(parent).forEach(term ->
                document.add(new StringField(FIELD_EXACT, term, Field.Store.NO)));
        return document;
    }

    private Set<String> exactTerms(TemplateDoc document) {
        Set<String> terms = new TreeSet<>();
        addTerm(terms, document.getFramework());
        addTerm(terms, document.getLanguage());
        addTerm(terms, document.getBuildTool());
        addTerms(terms, document.getTech());
        if (document.getDependencies() != null) {
            addTerms(terms, document.getDependencies().keySet());
        }
        if (document.getDevDependencies() != null) {
            addTerms(terms, document.getDevDependencies().keySet());
        }
        if (document.getFiles() != null) {
            addTerms(terms, document.getFiles().stream()
                    .map(TemplateDoc.TemplateFile::getPath)
                    .toList());
        }
        return terms;
    }

    private void addTerms(Set<String> terms, Iterable<String> values) {
        values.forEach(value -> addTerm(terms, value));
    }

    private void addTerm(Set<String> terms, String value) {
        if (value != null && !value.isBlank()) {
            terms.addAll(normalizeExactTerms(value));
        }
    }

    private Query createQuery(String rawQuery, RagDocumentKind documentKind) throws IOException {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String token : analyze(rawQuery)) {
            query.add(new TermQuery(new Term(FIELD_TEXT, token)), BooleanClause.Occur.SHOULD);
        }
        for (String exactTerm : exactQueryTerms(rawQuery)) {
            Query exactQuery = new TermQuery(new Term(FIELD_EXACT, exactTerm));
            query.add(new BoostQuery(exactQuery, 2.0f), BooleanClause.Occur.SHOULD);
        }
        query.add(new TermQuery(new Term(FIELD_DOCUMENT_KIND, documentKind.name())),
                BooleanClause.Occur.FILTER);
        query.setMinimumNumberShouldMatch(1);
        return query.build();
    }

    private Set<String> analyze(String query) throws IOException {
        Set<String> tokens = new LinkedHashSet<>();
        try (TokenStream tokenStream = analyzer.tokenStream(FIELD_TEXT, new StringReader(query))) {
            CharTermAttribute term = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                tokens.add(term.toString());
            }
            tokenStream.end();
        }
        return tokens;
    }

    private Set<String> exactQueryTerms(String query) {
        return normalizeExactTerms(query);
    }

    private Set<String> normalizeExactTerms(String value) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = value.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        terms.add(normalized);
        if (normalized.contains(" ")) {
            terms.addAll(List.of(normalized.split(" ")));
        }
        return terms;
    }

    private List<RankedCandidate> aggregate(ScoreDoc[] scoreDocs,
                                            RagDocumentKind documentKind,
                                            int topK) throws IOException {
        Map<String, Double> maxScores = new java.util.HashMap<>();
        for (ScoreDoc scoreDoc : scoreDocs) {
            String documentId = searcher.storedFields()
                    .document(scoreDoc.doc)
                    .get(FIELD_DOCUMENT_ID);
            maxScores.merge(documentId, (double) scoreDoc.score, Math::max);
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(maxScores.entrySet());
        ranked.sort(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));

        List<RankedCandidate> candidates = new ArrayList<>();
        int limit = Math.min(topK, ranked.size());
        for (int index = 0; index < limit; index++) {
            Map.Entry<String, Double> entry = ranked.get(index);
            candidates.add(new RankedCandidate(entry.getKey(), documentKind, index + 1, entry.getValue()));
        }
        return List.copyOf(candidates);
    }

    @Override
    public void close() throws IOException {
        reader.close();
        analyzer.close();
        directory.close();
    }
}
