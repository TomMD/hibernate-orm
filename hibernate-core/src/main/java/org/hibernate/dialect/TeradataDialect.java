/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.dialect;

import org.hibernate.LockOptions;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.relational.QualifiedNameImpl;
import org.hibernate.boot.model.relational.QualifiedTableName;
import org.hibernate.cfg.Environment;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.identity.Teradata14IdentityColumnSupport;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.TopLimitHandler;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtracter;
import org.hibernate.exception.spi.ViolatedConstraintNameExtracter;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Index;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.query.TemporalUnit;
import org.hibernate.query.spi.QueryEngine;
import org.hibernate.query.spi.QueryOptions;
import org.hibernate.query.sqm.mutation.internal.idtable.AfterUseAction;
import org.hibernate.query.sqm.mutation.internal.idtable.GlobalTemporaryTableStrategy;
import org.hibernate.query.sqm.mutation.internal.idtable.IdTable;
import org.hibernate.query.sqm.mutation.internal.idtable.TempIdTableExporter;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategy;
import org.hibernate.sql.ForUpdateFragment;
import org.hibernate.tool.schema.internal.StandardIndexExporter;
import org.hibernate.tool.schema.spi.Exporter;
import org.hibernate.type.StandardBasicTypes;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Iterator;
import java.util.Map;

/**
 * A dialect for the Teradata database created by MCR as part of the
 * dialect certification process.
 *
 * @author Jay Nance
 */
public class TeradataDialect extends Dialect {

	private int version;

	int getVersion() {
		return version;
	}
	
	private static final int PARAM_LIST_SIZE_LIMIT = 1024;

	public TeradataDialect(DialectResolutionInfo info) {
		this( info.getDatabaseMajorVersion() );
	}

	public TeradataDialect() {
		this(12);
	}

	public TeradataDialect(int version) {
		super();
		this.version = version;

		registerColumnType( Types.BOOLEAN, "byteint" );
		registerColumnType( Types.BIT, 1, "byteint" );
		registerColumnType( Types.BIT, "byteint" );

		registerColumnType( Types.TINYINT, "byteint" );

		registerColumnType( Types.BINARY, "byte($l)" );
		registerColumnType( Types.VARBINARY, "varbyte($l)" );

		if ( getVersion() < 13 ) {
			registerColumnType( Types.BIGINT, "numeric(19,0)" );
		}
		else {
			//'bigint' has been there since at least version 13
			registerColumnType( Types.BIGINT, "bigint" );
		}

		registerKeyword( "password" );
		registerKeyword( "type" );
		registerKeyword( "title" );
		registerKeyword( "year" );
		registerKeyword( "month" );
		registerKeyword( "summary" );
		registerKeyword( "alias" );
		registerKeyword( "value" );
		registerKeyword( "first" );
		registerKeyword( "role" );
		registerKeyword( "account" );
		registerKeyword( "class" );

		if ( getVersion() < 14 ) {
			// use getBytes instead of getBinaryStream
			getDefaultProperties().setProperty( Environment.USE_STREAMS_FOR_BINARY, "false" );
			// no batch statements
			getDefaultProperties().setProperty( Environment.STATEMENT_BATCH_SIZE, NO_BATCH );
		}
		else {
			getDefaultProperties().setProperty( Environment.USE_STREAMS_FOR_BINARY, "true" );
			getDefaultProperties().setProperty( Environment.STATEMENT_BATCH_SIZE, DEFAULT_BATCH_SIZE );
		}

	}

	@Override
	public int getPreferredSqlTypeCodeForBoolean() {
		return Types.BIT;
	}

	@Override
	public int getDefaultDecimalPrecision() {
		return getVersion() < 14 ? 18 : 38;
	}

	@Override
	public long getFractionalSecondPrecisionInNanos() {
	 	// Do duration arithmetic in a seconds, but
		// with the fractional part
		return 1_000_000_000; //seconds!!
	}

	public String timestampdiffPattern(TemporalUnit unit, boolean fromTimestamp, boolean toTimestamp) {
		StringBuilder pattern = new StringBuilder();
		//TODO: TOTALLY UNTESTED CODE!
		pattern.append("cast((?3 - ?2) ");
		switch (unit) {
			case NANOSECOND:
			case NATIVE:
				//default fractional precision is 6, the maximum
				pattern.append("second");
				break;
			case WEEK:
				pattern.append("day");
				break;
			case QUARTER:
				pattern.append("month");
				break;
			default:
				pattern.append( "?1" );
		}
		pattern.append("(4) as bigint)");
		switch (unit) {
			case WEEK:
				pattern.append("/7");
				break;
			case QUARTER:
				pattern.append("/3");
				break;
			case NANOSECOND:
				pattern.append("*1e9");
				break;
		}
		return pattern.toString();
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, boolean timestamp) {
		//TODO: TOTALLY UNTESTED CODE!
		switch ( unit ) {
			case NANOSECOND:
				return "(?3 + (?2)/1e9 * interval '1' second)";
			case NATIVE:
				return "(?3 + (?2) * interval '1' second)";
			case QUARTER:
				return "(?3 + (?2) * interval '3' month)";
			case WEEK:
				return "(?3 + (?2) * interval '7' day)";
			default:
				return "(?3 + (?2) * interval '1' ?1)";
		}
	}

	@Override
	public void initializeFunctionRegistry(QueryEngine queryEngine) {
		super.initializeFunctionRegistry( queryEngine );

		CommonFunctionFactory.concat_pipeOperator( queryEngine );
		CommonFunctionFactory.octetLength( queryEngine );
		CommonFunctionFactory.moreHyperbolic( queryEngine );
		CommonFunctionFactory.instr( queryEngine );
		CommonFunctionFactory.substr( queryEngine );
		CommonFunctionFactory.substring_substr( queryEngine );
		//also natively supports ANSI-style substring()
		CommonFunctionFactory.position( queryEngine );

		queryEngine.getSqmFunctionRegistry().patternDescriptorBuilder( "mod", "(?1 mod ?2)" )
				.setInvariantType( StandardBasicTypes.STRING )
				.setExactArgumentCount( 2 )
				.register();

		if ( getVersion() >= 14 ) {

			//list actually taken from Teradata 15 docs
			CommonFunctionFactory.lastDay( queryEngine );
			CommonFunctionFactory.initcap( queryEngine );
			CommonFunctionFactory.trim2( queryEngine );
			CommonFunctionFactory.soundex( queryEngine );
			CommonFunctionFactory.ascii( queryEngine );
			CommonFunctionFactory.char_chr( queryEngine );
			CommonFunctionFactory.trunc( queryEngine );
			CommonFunctionFactory.moreHyperbolic( queryEngine );
			CommonFunctionFactory.monthsBetween( queryEngine );
			CommonFunctionFactory.addMonths( queryEngine );
			CommonFunctionFactory.stddevPopSamp( queryEngine );
			CommonFunctionFactory.varPopSamp( queryEngine );
		}

	}

	/**
	 * Does this dialect support the <tt>FOR UPDATE</tt> syntax?
	 *
	 * @return empty string ... Teradata does not support <tt>FOR UPDATE<tt> syntax
	 */
	@Override
	public String getForUpdateString() {
		return "";
	}

	@Override
	public String getAddColumnString() {
		return getVersion() < 14 ? super.getAddColumnString() : "add";
	}

	@Override
	public SqmMultiTableMutationStrategy getFallbackSqmMutationStrategy(
			EntityMappingType rootEntityDescriptor,
			RuntimeModelCreationContext runtimeModelCreationContext) {
		return new GlobalTemporaryTableStrategy(
				new IdTable( rootEntityDescriptor, basename -> "HT_" + basename ),
				() -> new TempIdTableExporter( false, this::getTypeName ) {
					@Override
					public String getCreateOptions() {
						return "on commit preserve rows";
					}
				},
				AfterUseAction.CLEAN,
				runtimeModelCreationContext.getSessionFactory()
		);
	}

//	@Override
//	public String generateIdTableName(String baseName) {
//		return IdTableSupportStandardImpl.INSTANCE.generateIdTableName( baseName );
//	}
//
//	@Override
//	public String getCreateIdTableCommand() {
//		return "create global temporary table";
//	}
//
//	@Override
//	public String getCreateIdTableStatementOptions() {
//		return " on commit preserve rows";
//	}
//
//	@Override
//	public String getDropIdTableCommand() {
//		return "drop table";
//	}
//
//	@Override
//	public String getTruncateIdTableCommand() {
//		return "delete from";
//	}

	@Override
	public boolean supportsCascadeDelete() {
		return false;
	}

	@Override
	public boolean supportsCircularCascadeDeleteConstraints() {
		return false;
	}

	@Override
	public boolean supportsTupleDistinctCounts() {
		return false;
	}

	@Override
	public boolean supportsExistsInSelect() {
		return false;
	}

	@Override
	public boolean areStringComparisonsCaseInsensitive() {
		return getVersion() < 14;
	}

	@Override
	public boolean supportsEmptyInList() {
		return false;
	}

	@Override
	public String getSelectClauseNullString(int sqlType) {
		String v = "null";

		switch ( sqlType ) {
			case Types.BIT:
			case Types.TINYINT:
			case Types.SMALLINT:
			case Types.INTEGER:
			case Types.BIGINT:
			case Types.FLOAT:
			case Types.REAL:
			case Types.DOUBLE:
			case Types.NUMERIC:
			case Types.DECIMAL:
				v = "cast(null as decimal)";
				break;
			case Types.CHAR:
			case Types.VARCHAR:
			case Types.LONGVARCHAR:
				v = "cast(null as varchar(255))";
				break;
			case Types.DATE:
			case Types.TIME:
			case Types.TIMESTAMP:
			case Types.TIMESTAMP_WITH_TIMEZONE:
				v = "cast(null as timestamp)";
				break;
			case Types.BINARY:
			case Types.VARBINARY:
			case Types.LONGVARBINARY:
			case Types.NULL:
			case Types.OTHER:
			case Types.JAVA_OBJECT:
			case Types.DISTINCT:
			case Types.STRUCT:
			case Types.ARRAY:
			case Types.BLOB:
			case Types.CLOB:
			case Types.REF:
			case Types.DATALINK:
			case Types.BOOLEAN:
				break;
		}
		return v;
	}

	@Override
	public boolean supportsUnboundedLobLocatorMaterialization() {
		return false;
	}

	@Override
	public String getCreateMultisetTableString() {
		return "create multiset table ";
	}

	@Override
	public boolean supportsLobValueChangePropogation() {
		return false;
	}

	@Override
	public boolean doesReadCommittedCauseWritersToBlockReaders() {
		return true;
	}

	@Override
	public boolean doesRepeatableReadCauseReadersToBlockWriters() {
		return true;
	}

	@Override
	public boolean supportsBindAsCallableArgument() {
		return false;
	}

	@Override
	public int getInExpressionCountLimit() {
		return PARAM_LIST_SIZE_LIMIT;
	}

	@Override
	public int registerResultSetOutParameter(CallableStatement statement, int col) throws SQLException {
		statement.registerOutParameter( col, Types.REF );
		col++;
		return col;
	}

	@Override
	public ResultSet getResultSet(CallableStatement cs) throws SQLException {
		boolean isResultSet = cs.execute();
		while ( !isResultSet && cs.getUpdateCount() != -1 ) {
			isResultSet = cs.getMoreResults();
		}
		return cs.getResultSet();
	}

	@Override
	public ViolatedConstraintNameExtracter getViolatedConstraintNameExtracter() {
		return getVersion() < 14 ? super.getViolatedConstraintNameExtracter() : EXTRACTER;
	}

	private static ViolatedConstraintNameExtracter EXTRACTER = new TemplatedViolatedConstraintNameExtracter() {
		/**
		 * Extract the name of the violated constraint from the given SQLException.
		 *
		 * @param sqle The exception that was the result of the constraint violation.
		 * @return The extracted constraint name.
		 */
		@Override
		protected String doExtractConstraintName(SQLException sqle) throws NumberFormatException {
			String constraintName = null;

			int errorCode = sqle.getErrorCode();
			switch (errorCode) {
				case 27003:
					constraintName = extractUsingTemplate( "Unique constraint (", ") violated.", sqle.getMessage() );
					break;
				case 2700:
					constraintName = extractUsingTemplate( "Referential constraint", "violation:", sqle.getMessage() );
					break;
				case 5317:
					constraintName = extractUsingTemplate( "Check constraint (", ") violated.", sqle.getMessage() );
					break;
			}

			if ( constraintName != null ) {
				int i = constraintName.indexOf( '.' );
				if ( i != -1 ) {
					constraintName = constraintName.substring( i + 1 );
				}
			}
			return constraintName;
		}
	};

	@Override
	public boolean supportsLockTimeouts() {
		return false;
	}

	@Override
	public boolean useFollowOnLocking(String sql, QueryOptions queryOptions) {
		return getVersion() >= 14;
	}

	@Override
	public String getWriteLockString(int timeout) {
		if ( getVersion() < 14 ) {
			return super.getWriteLockString( timeout );
		}
		String sMsg = " Locking row for write ";
		if ( timeout == LockOptions.NO_WAIT ) {
			return sMsg + " nowait ";
		}
		return sMsg;
	}

	@Override
	public String getReadLockString(int timeout) {
		if ( getVersion() < 14 ) {
			return super.getReadLockString( timeout );
		}
		String sMsg = " Locking row for read  ";
		if ( timeout == LockOptions.NO_WAIT ) {
			return sMsg + " nowait ";
		}
		return sMsg;
	}

	@Override
	public Exporter<Index> getIndexExporter() {
		return new TeradataIndexExporter(this);
	}

	private static class TeradataIndexExporter extends StandardIndexExporter implements Exporter<Index> {

		private TeradataIndexExporter(Dialect dialect) {
			super(dialect);
		}

		@Override
		public String[] getSqlCreateStrings(Index index, Metadata metadata) {
			final JdbcEnvironment jdbcEnvironment = metadata.getDatabase().getJdbcEnvironment();
			QualifiedTableName qualifiedTableName = index.getTable().getQualifiedTableName();
			final String tableName = jdbcEnvironment.getQualifiedObjectNameFormatter().format(
					qualifiedTableName,
					jdbcEnvironment.getDialect()
			);

			final String indexNameForCreation;
			if ( getDialect().qualifyIndexName() ) {
				indexNameForCreation = jdbcEnvironment.getQualifiedObjectNameFormatter().format(
						new QualifiedNameImpl(
								qualifiedTableName.getCatalogName(),
								qualifiedTableName.getSchemaName(),
								Identifier.toIdentifier( index.getName() )
						),
						jdbcEnvironment.getDialect()
				);
			}
			else {
				indexNameForCreation = index.getName();
			}

			StringBuilder columnList = new StringBuilder();
			boolean first = true;
			for (Iterator<Column> column = index.getColumnIterator(); column.hasNext(); ) {
				if ( first ) {
					first = false;
				}
				else {
					columnList.append( ", " );
				}
				columnList.append( column.next().getName() );
			}

			return new String[] {
					"create index " + indexNameForCreation
							+ "(" + columnList + ") on " + tableName
			};
		}
	}

	@Override
	public IdentityColumnSupport getIdentityColumnSupport() {
		return getVersion() < 14
				? super.getIdentityColumnSupport()
				: new Teradata14IdentityColumnSupport();
	}

	@Override
	public String applyLocksToSql(String sql, LockOptions aliasedLockOptions, Map<String, String[]> keyColumnNames) {
		return getVersion() < 14
				? super.applyLocksToSql( sql, aliasedLockOptions, keyColumnNames )
				: new ForUpdateFragment( this, aliasedLockOptions, keyColumnNames ).toFragmentString() + " " + sql;
	}

	@Override
	public LimitHandler getLimitHandler() {
		//TODO: is this right?!
		return TopLimitHandler.INSTANCE;
	}
}
