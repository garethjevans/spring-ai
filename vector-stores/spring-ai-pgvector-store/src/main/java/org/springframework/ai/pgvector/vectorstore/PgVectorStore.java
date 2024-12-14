/*
 * Copyright 2023-2024 the original author or authors.
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

package org.springframework.ai.pgvector.vectorstore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.pgvector.PGvector;
import io.micrometer.observation.ObservationRegistry;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptionsBuilder;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.observation.conventions.VectorStoreProvider;
import org.springframework.ai.observation.conventions.VectorStoreSimilarityMetric;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.ai.vectorstore.AbstractVectorStoreBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionConverter;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * PostgreSQL-based vector store implementation using the pgvector extension.
 *
 * <p>
 * The store uses a database table to persist the vector embeddings along with their
 * associated document content and metadata. By default, it uses the "vector_store" table
 * in the "public" schema, but this can be configured.
 * </p>
 *
 * <p>
 * Features:
 * </p>
 * <ul>
 * <li>Automatic schema initialization with configurable table and index creation</li>
 * <li>Support for different distance metrics: Cosine, Euclidean, and Inner Product</li>
 * <li>Flexible indexing options: HNSW (default), IVFFlat, or exact search (no index)</li>
 * <li>Metadata filtering using JSON path expressions</li>
 * <li>Configurable similarity thresholds for search results</li>
 * <li>Batch processing support with configurable batch sizes</li>
 * </ul>
 *
 * <p>
 * Basic usage example:
 * </p>
 * <pre>{@code
 * PgVectorStore vectorStore = PgVectorStore.builder()
 *     .jdbcTemplate(jdbcTemplate)
 *     .embeddingModel(embeddingModel)
 *     .dimensions(1536) // Optional: defaults to model dimensions or 1536
 *     .distanceType(PgDistanceType.COSINE_DISTANCE)
 *     .indexType(PgIndexType.HNSW)
 *     .build();
 *
 * // Add documents
 * vectorStore.add(List.of(
 *     new Document("content1", Map.of("key1", "value1")),
 *     new Document("content2", Map.of("key2", "value2"))
 * ));
 *
 * // Search with filters
 * List<Document> results = vectorStore.similaritySearch(
 *     SearchRequest.query("search text")
 *         .withTopK(5)
 *         .withSimilarityThreshold(0.7)
 *         .withFilterExpression("key1 == 'value1'")
 * );
 * }</pre>
 *
 * <p>
 * Advanced configuration example:
 * </p>
 * <pre>{@code
 * PgVectorStore vectorStore = PgVectorStore.builder()
 *     .jdbcTemplate(jdbcTemplate)
 *     .embeddingModel(embeddingModel)
 *     .schemaName("custom_schema")
 *     .vectorTableName("custom_vectors")
 *     .distanceType(PgDistanceType.NEGATIVE_INNER_PRODUCT)
 *     .removeExistingVectorStoreTable(true)
 *     .initializeSchema(true)
 *     .maxDocumentBatchSize(1000)
 *     .build();
 * }</pre>
 *
 * <p>
 * Database Requirements:
 * </p>
 * <ul>
 * <li>PostgreSQL with pgvector extension installed</li>
 * <li>Required extensions: vector, hstore, uuid-ossp</li>
 * <li>Table schema with id (uuid), content (text), metadata (json), and embedding
 * (vector) columns</li>
 * </ul>
 *
 * <p>
 * Distance Types:
 * </p>
 * <ul>
 * <li>COSINE_DISTANCE: Default, suitable for most use cases</li>
 * <li>EUCLIDEAN_DISTANCE: L2 distance between vectors</li>
 * <li>NEGATIVE_INNER_PRODUCT: Best performance for normalized vectors (e.g., OpenAI
 * embeddings)</li>
 * </ul>
 *
 * <p>
 * Index Types:
 * </p>
 * <ul>
 * <li>HNSW: Default, better query performance but slower builds and more memory</li>
 * <li>IVFFLAT: Faster builds, less memory, but lower query performance</li>
 * <li>NONE: Exact search without indexing</li>
 * </ul>
 *
 * @author Christian Tzolov
 * @author Josh Long
 * @author Muthukumaran Navaneethakrishnan
 * @author Thomas Vitale
 * @author Soby Chacko
 * @author Sebastien Deleuze
 * @since 1.0.0
 */
public class PgVectorStore extends AbstractObservationVectorStore implements InitializingBean {

	public static final int OPENAI_EMBEDDING_DIMENSION_SIZE = 1536;

	public static final int INVALID_EMBEDDING_DIMENSION = -1;

	public static final String DEFAULT_TABLE_NAME = "vector_store";

	public static final String DEFAULT_VECTOR_INDEX_NAME = "spring_ai_vector_index";

	public static final String DEFAULT_SCHEMA_NAME = "public";

	public static final boolean DEFAULT_SCHEMA_VALIDATION = false;

	public static final int MAX_DOCUMENT_BATCH_SIZE = 10_000;

	private static final Logger logger = LoggerFactory.getLogger(PgVectorStore.class);

	private static Map<PgDistanceType, VectorStoreSimilarityMetric> SIMILARITY_TYPE_MAPPING = Map.of(
			PgDistanceType.COSINE_DISTANCE, VectorStoreSimilarityMetric.COSINE, PgDistanceType.EUCLIDEAN_DISTANCE,
			VectorStoreSimilarityMetric.EUCLIDEAN, PgDistanceType.NEGATIVE_INNER_PRODUCT,
			VectorStoreSimilarityMetric.DOT);

	public final FilterExpressionConverter filterExpressionConverter = new PgVectorFilterExpressionConverter();

	private final String vectorTableName;

	private final String vectorIndexName;

	private final JdbcTemplate jdbcTemplate;

	private final String schemaName;

	private final boolean schemaValidation;

	private final boolean initializeSchema;

	private final int dimensions;

	private final PgDistanceType distanceType;

	private final ObjectMapper objectMapper;

	private final boolean removeExistingVectorStoreTable;

	private final PgIndexType createIndexMethod;

	private final PgVectorSchemaValidator schemaValidator;

	private final BatchingStrategy batchingStrategy;

	private final int maxDocumentBatchSize;

	@Deprecated(forRemoval = true, since = "1.0.0-M5")
	public PgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
		this(jdbcTemplate, embeddingModel, INVALID_EMBEDDING_DIMENSION, PgDistanceType.COSINE_DISTANCE, false,
				PgIndexType.NONE, false);
	}

	@Deprecated(forRemoval = true, since = "1.0.0-M5")
	public PgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, int dimensions) {
		this(jdbcTemplate, embeddingModel, dimensions, PgDistanceType.COSINE_DISTANCE, false, PgIndexType.NONE, false);
	}

	@Deprecated(forRemoval = true, since = "1.0.0-M5")
	public PgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, int dimensions,
			PgDistanceType distanceType, boolean removeExistingVectorStoreTable, PgIndexType createIndexMethod,
			boolean initializeSchema) {

		this(DEFAULT_TABLE_NAME, jdbcTemplate, embeddingModel, dimensions, distanceType, removeExistingVectorStoreTable,
				createIndexMethod, initializeSchema);
	}

	@Deprecated(forRemoval = true, since = "1.0.0-M5")
	public PgVectorStore(String vectorTableName, JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
			int dimensions, PgDistanceType distanceType, boolean removeExistingVectorStoreTable,
			PgIndexType createIndexMethod, boolean initializeSchema) {

		this(builder().jdbcTemplate(jdbcTemplate)
			.schemaName(DEFAULT_SCHEMA_NAME)
			.vectorTableName(vectorTableName)
			.vectorTableValidationsEnabled(DEFAULT_SCHEMA_VALIDATION)
			.dimensions(dimensions)
			.distanceType(distanceType)
			.removeExistingVectorStoreTable(removeExistingVectorStoreTable)
			.indexType(createIndexMethod)
			.initializeSchema(initializeSchema));
	}

	/**
	 * @param builder {@link VectorStore.Builder} for pg vector store
	 */
	protected PgVectorStore(PgVectorStoreBuilder builder) {
		super(builder);

		Assert.notNull(builder.jdbcTemplate, "JdbcTemplate must not be null");

		this.objectMapper = JsonMapper.builder().addModules(JacksonUtils.instantiateAvailableModules()).build();

		String vectorTable = builder.vectorTableName;
		this.vectorTableName = (null == vectorTable || vectorTable.isEmpty()) ? DEFAULT_TABLE_NAME : vectorTable.trim();
		logger.info("Using the vector table name: {}. Is empty: {}", this.vectorTableName,
				(this.vectorTableName == null || this.vectorTableName.isEmpty()));

		this.vectorIndexName = this.vectorTableName.equals(DEFAULT_TABLE_NAME) ? DEFAULT_VECTOR_INDEX_NAME
				: this.vectorTableName + "_index";

		this.schemaName = builder.schemaName;
		this.schemaValidation = builder.vectorTableValidationsEnabled;

		this.jdbcTemplate = builder.jdbcTemplate;
		this.dimensions = builder.dimensions;
		this.distanceType = builder.distanceType;
		this.removeExistingVectorStoreTable = builder.removeExistingVectorStoreTable;
		this.createIndexMethod = builder.indexType;
		this.initializeSchema = builder.initializeSchema;
		this.schemaValidator = new PgVectorSchemaValidator(this.jdbcTemplate);
		this.batchingStrategy = builder.batchingStrategy;
		this.maxDocumentBatchSize = builder.maxDocumentBatchSize;
	}

	public PgDistanceType getDistanceType() {
		return this.distanceType;
	}

	public static PgVectorStoreBuilder builder() {
		return new PgVectorStoreBuilder();
	}

	@Override
	public void doAdd(List<Document> documents) {
		List<float[]> embeddings = this.embeddingModel.embed(documents, EmbeddingOptionsBuilder.builder().build(),
				this.batchingStrategy);

		List<List<Document>> batchedDocuments = batchDocuments(documents);
		batchedDocuments.forEach(batchDocument -> insertOrUpdateBatch(batchDocument, documents, embeddings));
	}

	private List<List<Document>> batchDocuments(List<Document> documents) {
		List<List<Document>> batches = new ArrayList<>();
		for (int i = 0; i < documents.size(); i += this.maxDocumentBatchSize) {
			batches.add(documents.subList(i, Math.min(i + this.maxDocumentBatchSize, documents.size())));
		}
		return batches;
	}

	private void insertOrUpdateBatch(List<Document> batch, List<Document> documents, List<float[]> embeddings) {
		String sql = "INSERT INTO " + getFullyQualifiedTableName()
				+ " (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?) " + "ON CONFLICT (id) DO "
				+ "UPDATE SET content = ? , metadata = ?::jsonb , embedding = ? ";

		this.jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {

			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {

				var document = batch.get(i);
				var content = document.getContent();
				var json = toJson(document.getMetadata());
				var embedding = embeddings.get(documents.indexOf(document));
				var pGvector = new PGvector(embedding);

				StatementCreatorUtils.setParameterValue(ps, 1, SqlTypeValue.TYPE_UNKNOWN,
						UUID.fromString(document.getId()));
				StatementCreatorUtils.setParameterValue(ps, 2, SqlTypeValue.TYPE_UNKNOWN, content);
				StatementCreatorUtils.setParameterValue(ps, 3, SqlTypeValue.TYPE_UNKNOWN, json);
				StatementCreatorUtils.setParameterValue(ps, 4, SqlTypeValue.TYPE_UNKNOWN, pGvector);
				StatementCreatorUtils.setParameterValue(ps, 5, SqlTypeValue.TYPE_UNKNOWN, content);
				StatementCreatorUtils.setParameterValue(ps, 6, SqlTypeValue.TYPE_UNKNOWN, json);
				StatementCreatorUtils.setParameterValue(ps, 7, SqlTypeValue.TYPE_UNKNOWN, pGvector);
			}

			@Override
			public int getBatchSize() {
				return batch.size();
			}
		});
	}

	private String toJson(Map<String, Object> map) {
		try {
			return this.objectMapper.writeValueAsString(map);
		}
		catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Optional<Boolean> doDelete(List<String> idList) {
		int updateCount = 0;
		for (String id : idList) {
			int count = this.jdbcTemplate.update("DELETE FROM " + getFullyQualifiedTableName() + " WHERE id = ?",
					UUID.fromString(id));
			updateCount = updateCount + count;
		}

		return Optional.of(updateCount == idList.size());
	}

	@Override
	public List<Document> doSimilaritySearch(SearchRequest request) {

		String nativeFilterExpression = (request.getFilterExpression() != null)
				? this.filterExpressionConverter.convertExpression(request.getFilterExpression()) : "";

		String jsonPathFilter = "";

		if (StringUtils.hasText(nativeFilterExpression)) {
			jsonPathFilter = " AND metadata::jsonb @@ '" + nativeFilterExpression + "'::jsonpath ";
		}

		double distance = 1 - request.getSimilarityThreshold();

		PGvector queryEmbedding = getQueryEmbedding(request.getQuery());

		return this.jdbcTemplate.query(
				String.format(this.getDistanceType().similaritySearchSqlTemplate, getFullyQualifiedTableName(),
						jsonPathFilter),
				new DocumentRowMapper(this.objectMapper), queryEmbedding, queryEmbedding, distance, request.getTopK());
	}

	public List<Double> embeddingDistance(String query) {
		return this.jdbcTemplate.query(
				"SELECT embedding " + this.comparisonOperator() + " ? AS distance FROM " + getFullyQualifiedTableName(),
				new RowMapper<Double>() {

					@Override
					@Nullable
					public Double mapRow(ResultSet rs, int rowNum) throws SQLException {
						return rs.getDouble(DocumentRowMapper.COLUMN_DISTANCE);
					}

				}, getQueryEmbedding(query));
	}

	private PGvector getQueryEmbedding(String query) {
		float[] embedding = this.embeddingModel.embed(query);
		return new PGvector(embedding);
	}

	private String comparisonOperator() {
		return this.getDistanceType().operator;
	}

	// ---------------------------------------------------------------------------------
	// Initialize
	// ---------------------------------------------------------------------------------
	@Override
	public void afterPropertiesSet() {

		logger.info("Initializing PGVectorStore schema for table: {} in schema: {}", this.getVectorTableName(),
				this.getSchemaName());

		logger.info("vectorTableValidationsEnabled {}", this.schemaValidation);

		if (this.schemaValidation) {
			this.schemaValidator.validateTableSchema(this.getSchemaName(), this.getVectorTableName());
		}

		if (!this.initializeSchema) {
			logger.debug("Skipping the schema initialization for the table: {}", this.getFullyQualifiedTableName());
			return;
		}

		// Enable the PGVector, JSONB and UUID support.
		this.jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
		this.jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS hstore");
		this.jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");

		this.jdbcTemplate.execute(String.format("CREATE SCHEMA IF NOT EXISTS %s", this.getSchemaName()));

		// Remove existing VectorStoreTable
		if (this.removeExistingVectorStoreTable) {
			this.jdbcTemplate.execute(String.format("DROP TABLE IF EXISTS %s", this.getFullyQualifiedTableName()));
		}

		this.jdbcTemplate.execute(String.format("""
				CREATE TABLE IF NOT EXISTS %s (
					id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
					content text,
					metadata json,
					embedding vector(%d)
				)
				""", this.getFullyQualifiedTableName(), this.embeddingDimensions()));

		if (this.createIndexMethod != PgIndexType.NONE) {
			this.jdbcTemplate.execute(String.format("""
					CREATE INDEX IF NOT EXISTS %s ON %s USING %s (embedding %s)
					""", this.getVectorIndexName(), this.getFullyQualifiedTableName(), this.createIndexMethod,
					this.getDistanceType().index));
		}
	}

	private String getFullyQualifiedTableName() {
		return this.schemaName + "." + this.vectorTableName;
	}

	private String getVectorTableName() {
		return this.vectorTableName;
	}

	private String getSchemaName() {
		return this.schemaName;
	}

	private String getVectorIndexName() {
		return this.vectorIndexName;
	}

	int embeddingDimensions() {
		// The manually set dimensions have precedence over the computed one.
		if (this.dimensions > 0) {
			return this.dimensions;
		}

		try {
			int embeddingDimensions = this.embeddingModel.dimensions();
			if (embeddingDimensions > 0) {
				return embeddingDimensions;
			}
		}
		catch (Exception e) {
			logger.warn("Failed to obtain the embedding dimensions from the embedding model and fall backs to default:"
					+ OPENAI_EMBEDDING_DIMENSION_SIZE, e);
		}
		return OPENAI_EMBEDDING_DIMENSION_SIZE;
	}

	@Override
	public VectorStoreObservationContext.Builder createObservationContextBuilder(String operationName) {

		return VectorStoreObservationContext.builder(VectorStoreProvider.PG_VECTOR.value(), operationName)
			.withCollectionName(this.vectorTableName)
			.withDimensions(this.embeddingDimensions())
			.withNamespace(this.schemaName)
			.withSimilarityMetric(getSimilarityMetric());
	}

	private String getSimilarityMetric() {
		if (!SIMILARITY_TYPE_MAPPING.containsKey(this.getDistanceType())) {
			return this.getDistanceType().name();
		}
		return SIMILARITY_TYPE_MAPPING.get(this.distanceType).value();
	}

	/**
	 * By default, pgvector performs exact nearest neighbor search, which provides perfect
	 * recall. You can add an index to use approximate nearest neighbor search, which
	 * trades some recall for speed. Unlike typical indexes, you will see different
	 * results for queries after adding an approximate index.
	 */
	public enum PgIndexType {

		/**
		 * Performs exact nearest neighbor search, which provides perfect recall.
		 */
		NONE,
		/**
		 * An IVFFlat index divides vectors into lists, and then searches a subset of
		 * those lists that are closest to the query vector. It has faster build times and
		 * uses less memory than HNSW, but has lower query performance (in terms of
		 * speed-recall tradeoff).
		 */
		IVFFLAT,
		/**
		 * An HNSW index creates a multilayer graph. It has slower build times and uses
		 * more memory than IVFFlat, but has better query performance (in terms of
		 * speed-recall tradeoff). There’s no training step like IVFFlat, so the index can
		 * be created without any data in the table.
		 */
		HNSW

	}

	/**
	 * Defaults to CosineDistance. But if vectors are normalized to length 1 (like OpenAI
	 * embeddings), use inner product (NegativeInnerProduct) for best performance.
	 */
	public enum PgDistanceType {

		// NOTE: works only if If vectors are normalized to length 1 (like OpenAI
		// embeddings), use inner product for best performance.
		// The Sentence transformers are NOT normalized:
		// https://github.com/UKPLab/sentence-transformers/issues/233
		EUCLIDEAN_DISTANCE("<->", "vector_l2_ops",
				"SELECT *, embedding <-> ? AS distance FROM %s WHERE embedding <-> ? < ? %s ORDER BY distance LIMIT ? "),

		// NOTE: works only if If vectors are normalized to length 1 (like OpenAI
		// embeddings), use inner product for best performance.
		// The Sentence transformers are NOT normalized:
		// https://github.com/UKPLab/sentence-transformers/issues/233
		NEGATIVE_INNER_PRODUCT("<#>", "vector_ip_ops",
				"SELECT *, (1 + (embedding <#> ?)) AS distance FROM %s WHERE (1 + (embedding <#> ?)) < ? %s ORDER BY distance LIMIT ? "),

		COSINE_DISTANCE("<=>", "vector_cosine_ops",
				"SELECT *, embedding <=> ? AS distance FROM %s WHERE embedding <=> ? < ? %s ORDER BY distance LIMIT ? ");

		public final String operator;

		public final String index;

		public final String similaritySearchSqlTemplate;

		PgDistanceType(String operator, String index, String sqlTemplate) {
			this.operator = operator;
			this.index = index;
			this.similaritySearchSqlTemplate = sqlTemplate;
		}

	}

	private static class DocumentRowMapper implements RowMapper<Document> {

		private static final String COLUMN_EMBEDDING = "embedding";

		private static final String COLUMN_METADATA = "metadata";

		private static final String COLUMN_ID = "id";

		private static final String COLUMN_CONTENT = "content";

		private static final String COLUMN_DISTANCE = "distance";

		private final ObjectMapper objectMapper;

		DocumentRowMapper(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
		}

		@Override
		public Document mapRow(ResultSet rs, int rowNum) throws SQLException {
			String id = rs.getString(COLUMN_ID);
			String content = rs.getString(COLUMN_CONTENT);
			PGobject pgMetadata = rs.getObject(COLUMN_METADATA, PGobject.class);
			Float distance = rs.getFloat(COLUMN_DISTANCE);

			Map<String, Object> metadata = toMap(pgMetadata);
			metadata.put(DocumentMetadata.DISTANCE.value(), distance);

			// @formatter:off
			return Document.builder()
				.id(id)
				.text(content)
				.metadata(metadata)
				.score(1.0 - distance)
				.build(); // @formatter:on
		}

		private Map<String, Object> toMap(PGobject pgObject) {

			String source = pgObject.getValue();
			try {
				return (Map<String, Object>) this.objectMapper.readValue(source, Map.class);
			}
			catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			}
		}

	}

	public static class PgVectorStoreBuilder extends AbstractVectorStoreBuilder<PgVectorStoreBuilder> {

		private JdbcTemplate jdbcTemplate;

		private String schemaName = PgVectorStore.DEFAULT_SCHEMA_NAME;

		private String vectorTableName = PgVectorStore.DEFAULT_TABLE_NAME;

		private boolean vectorTableValidationsEnabled = PgVectorStore.DEFAULT_SCHEMA_VALIDATION;

		private int dimensions = PgVectorStore.INVALID_EMBEDDING_DIMENSION;

		private PgDistanceType distanceType = PgDistanceType.COSINE_DISTANCE;

		private boolean removeExistingVectorStoreTable = false;

		private PgIndexType indexType = PgIndexType.HNSW;

		private boolean initializeSchema;

		private BatchingStrategy batchingStrategy = new TokenCountBatchingStrategy();

		private int maxDocumentBatchSize = MAX_DOCUMENT_BATCH_SIZE;

		public PgVectorStoreBuilder jdbcTemplate(JdbcTemplate jdbcTemplate) {
			Assert.notNull(jdbcTemplate, "JdbcTemplate must not be null");
			this.jdbcTemplate = jdbcTemplate;
			return this;
		}

		public PgVectorStoreBuilder schemaName(String schemaName) {
			this.schemaName = schemaName;
			return this;
		}

		public PgVectorStoreBuilder vectorTableName(String vectorTableName) {
			this.vectorTableName = vectorTableName;
			return this;
		}

		public PgVectorStoreBuilder vectorTableValidationsEnabled(boolean vectorTableValidationsEnabled) {
			this.vectorTableValidationsEnabled = vectorTableValidationsEnabled;
			return this;
		}

		public PgVectorStoreBuilder dimensions(int dimensions) {
			this.dimensions = dimensions;
			return this;
		}

		public PgVectorStoreBuilder distanceType(PgDistanceType distanceType) {
			this.distanceType = distanceType;
			return this;
		}

		public PgVectorStoreBuilder removeExistingVectorStoreTable(boolean removeExistingVectorStoreTable) {
			this.removeExistingVectorStoreTable = removeExistingVectorStoreTable;
			return this;
		}

		public PgVectorStoreBuilder indexType(PgIndexType indexType) {
			this.indexType = indexType;
			return this;
		}

		public PgVectorStoreBuilder initializeSchema(boolean initializeSchema) {
			this.initializeSchema = initializeSchema;
			return this;
		}

		public PgVectorStoreBuilder batchingStrategy(BatchingStrategy batchingStrategy) {
			this.batchingStrategy = batchingStrategy;
			return this;
		}

		public PgVectorStoreBuilder maxDocumentBatchSize(int maxDocumentBatchSize) {
			this.maxDocumentBatchSize = maxDocumentBatchSize;
			return this;
		}

		public PgVectorStore build() {
			validate();
			return new PgVectorStore(this);
		}

	}

	@Deprecated(forRemoval = true, since = "1.0.0-M5")
	public static class Builder {

		private final JdbcTemplate jdbcTemplate;

		private final EmbeddingModel embeddingModel;

		private String schemaName = PgVectorStore.DEFAULT_SCHEMA_NAME;

		private String vectorTableName;

		private boolean vectorTableValidationsEnabled = PgVectorStore.DEFAULT_SCHEMA_VALIDATION;

		private int dimensions = PgVectorStore.INVALID_EMBEDDING_DIMENSION;

		private PgDistanceType distanceType = PgDistanceType.COSINE_DISTANCE;

		private boolean removeExistingVectorStoreTable = false;

		private PgIndexType indexType = PgIndexType.HNSW;

		private boolean initializeSchema;

		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		private BatchingStrategy batchingStrategy = new TokenCountBatchingStrategy();

		private int maxDocumentBatchSize = MAX_DOCUMENT_BATCH_SIZE;

		@Nullable
		private VectorStoreObservationConvention searchObservationConvention;

		public Builder(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
			if (jdbcTemplate == null || embeddingModel == null) {
				throw new IllegalArgumentException("JdbcTemplate and EmbeddingModel must not be null");
			}
			this.jdbcTemplate = jdbcTemplate;
			this.embeddingModel = embeddingModel;
		}

		public Builder withSchemaName(String schemaName) {
			this.schemaName = schemaName;
			return this;
		}

		public Builder withVectorTableName(String vectorTableName) {
			this.vectorTableName = vectorTableName;
			return this;
		}

		public Builder withVectorTableValidationsEnabled(boolean vectorTableValidationsEnabled) {
			this.vectorTableValidationsEnabled = vectorTableValidationsEnabled;
			return this;
		}

		public Builder withDimensions(int dimensions) {
			this.dimensions = dimensions;
			return this;
		}

		public Builder withDistanceType(PgDistanceType distanceType) {
			this.distanceType = distanceType;
			return this;
		}

		public Builder withRemoveExistingVectorStoreTable(boolean removeExistingVectorStoreTable) {
			this.removeExistingVectorStoreTable = removeExistingVectorStoreTable;
			return this;
		}

		public Builder withIndexType(PgIndexType indexType) {
			this.indexType = indexType;
			return this;
		}

		public Builder withInitializeSchema(boolean initializeSchema) {
			this.initializeSchema = initializeSchema;
			return this;
		}

		public Builder withObservationRegistry(ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		public Builder withSearchObservationConvention(VectorStoreObservationConvention customObservationConvention) {
			this.searchObservationConvention = customObservationConvention;
			return this;
		}

		public Builder withBatchingStrategy(BatchingStrategy batchingStrategy) {
			this.batchingStrategy = batchingStrategy;
			return this;
		}

		public Builder withMaxDocumentBatchSize(int maxDocumentBatchSize) {
			this.maxDocumentBatchSize = maxDocumentBatchSize;
			return this;
		}

		public PgVectorStore build() {
			return PgVectorStore.builder()
				.jdbcTemplate(this.jdbcTemplate)
				.embeddingModel(this.embeddingModel)
				.schemaName(this.schemaName)
				.vectorTableName(this.vectorTableName)
				.vectorTableValidationsEnabled(this.vectorTableValidationsEnabled)
				.dimensions(this.dimensions)
				.distanceType(this.distanceType)
				.removeExistingVectorStoreTable(this.removeExistingVectorStoreTable)
				.indexType(this.indexType)
				.initializeSchema(this.initializeSchema)
				.batchingStrategy(this.batchingStrategy)
				.maxDocumentBatchSize(this.maxDocumentBatchSize)
				.build();
		}

	}

}