/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.dialect;

import org.hibernate.JDBCException;
import org.hibernate.LockMode;
import org.hibernate.NotYetImplementedFor6Exception;
import org.hibernate.StaleObjectStateException;
import org.hibernate.boot.TempTableDdlTransactionHandling;
import org.hibernate.cfg.Environment;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.dialect.identity.HSQLIdentityColumnSupport;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.lock.*;
import org.hibernate.dialect.pagination.LegacyHSQLLimitHandler;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.LimitOffsetLimitHandler;
import org.hibernate.dialect.pagination.OffsetFetchLimitHandler;
import org.hibernate.dialect.sequence.HSQLSequenceSupport;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.jdbc.env.spi.NameQualifierSupport;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtracter;
import org.hibernate.exception.spi.ViolatedConstraintNameExtracter;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.internal.util.JdbcExceptionHelper;
import org.hibernate.internal.util.ReflectHelper;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.persister.entity.Lockable;
import org.hibernate.query.CastType;
import org.hibernate.query.TemporalUnit;
import org.hibernate.query.spi.QueryEngine;
import org.hibernate.query.sqm.mutation.internal.idtable.AfterUseAction;
import org.hibernate.query.sqm.mutation.internal.idtable.GlobalTemporaryTableStrategy;
import org.hibernate.query.sqm.mutation.internal.idtable.IdTable;
import org.hibernate.query.sqm.mutation.internal.idtable.LocalTemporaryTableStrategy;
import org.hibernate.query.sqm.mutation.internal.idtable.TempIdTableExporter;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategy;
import org.hibernate.tool.schema.extract.internal.SequenceInformationExtractorHSQLDBDatabaseImpl;
import org.hibernate.tool.schema.extract.spi.SequenceInformationExtractor;
import org.jboss.logging.Logger;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Types;

import static org.hibernate.query.CastType.*;

/**
 * An SQL dialect compatible with HyperSQL (HSQLDB) version 1.8 and above.
 *
 * @author Christoph Sturm
 * @author Phillip Baird
 * @author Fred Toussi
 */
public class HSQLDialect extends Dialect {
	private static final CoreMessageLogger LOG = Logger.getMessageLogger(
			CoreMessageLogger.class,
			HSQLDialect.class.getName()
	);

	/**
	 * version is 180 for 1.8.0 or 200 for 2.0.0
	 */
	private final int version;

	int getVersion() {
		return version;
	}

	public HSQLDialect(DialectResolutionInfo info) {
		this( info.getDatabaseMajorVersion()*100 + info.getDatabaseMinorVersion()*10 );
	}

	public HSQLDialect() {
		this(180);
	}

	public HSQLDialect(int version) {
		super();

		if ( version == 180 ) {
			version = reflectedVersion(version);
		}

		this.version = version;

		//Note that all floating point types are synonyms for 'double'

		//HSQL has actual types called 'longvarchar' and
		//'longvarbinary', which one must suppose are meant
		//to be used for this purpose
		registerColumnType( Types.LONGVARCHAR, "longvarchar" ); //synonym for 'varchar(16M)'
		registerColumnType( Types.LONGVARBINARY, "longvarbinary" ); //synonym for 'varbinary(16M)'

		//HSQL has no 'nclob' type, but 'clob' is Unicode
		//(See HHH-10364)
		registerColumnType( Types.NCLOB, "clob" );

		if ( this.version < 200 ) {
			//Older versions of HSQL did not accept
			//precision for the 'numeric' type
			registerColumnType( Types.NUMERIC, "numeric" );

			//Older versions of HSQL had no lob support
			registerColumnType( Types.BLOB, "longvarbinary" );
			registerColumnType( Types.CLOB, "longvarchar" );
		}

		if ( this.version >= 250 ) {
			registerKeyword( "period" );
		}

		getDefaultProperties().setProperty( Environment.STATEMENT_BATCH_SIZE, DEFAULT_BATCH_SIZE );
	}

	private static int reflectedVersion(int version) {
		try {
			final Class props = ReflectHelper.classForName("org.hsqldb.persist.HsqlDatabaseProperties");
			final String versionString = (String) props.getDeclaredField("THIS_VERSION").get( null );

			return Integer.parseInt( versionString.substring(0, 1) ) * 100
				+  Integer.parseInt( versionString.substring(2, 3) ) * 10
				+  Integer.parseInt( versionString.substring(4, 5) );
		}
		catch (Throwable e) {
			// might be a very old version, or not accessible in class path
			return version;
		}
	}

	@Override
	public void initializeFunctionRegistry(QueryEngine queryEngine) {
		super.initializeFunctionRegistry( queryEngine );

		CommonFunctionFactory.cot( queryEngine );
		CommonFunctionFactory.radians( queryEngine );
		CommonFunctionFactory.degrees( queryEngine );
		CommonFunctionFactory.log10( queryEngine );
		CommonFunctionFactory.rand( queryEngine );
		CommonFunctionFactory.trunc( queryEngine );
		CommonFunctionFactory.truncate( queryEngine );
		CommonFunctionFactory.pi( queryEngine );
		CommonFunctionFactory.soundex( queryEngine );
		CommonFunctionFactory.reverse( queryEngine );
		CommonFunctionFactory.space( queryEngine );
		CommonFunctionFactory.repeat( queryEngine );
		CommonFunctionFactory.translate( queryEngine );
		CommonFunctionFactory.bitand( queryEngine );
		CommonFunctionFactory.bitor( queryEngine );
		CommonFunctionFactory.bitxor( queryEngine );
		CommonFunctionFactory.bitnot( queryEngine );
		CommonFunctionFactory.yearMonthDay( queryEngine );
		CommonFunctionFactory.hourMinuteSecond( queryEngine );
		CommonFunctionFactory.dayofweekmonthyear( queryEngine );
		CommonFunctionFactory.weekQuarter( queryEngine );
		CommonFunctionFactory.daynameMonthname( queryEngine );
		CommonFunctionFactory.lastDay( queryEngine );
		CommonFunctionFactory.trim1( queryEngine );
		CommonFunctionFactory.toCharNumberDateTimestamp( queryEngine );
		CommonFunctionFactory.concat_pipeOperator( queryEngine );
		CommonFunctionFactory.localtimeLocaltimestamp( queryEngine );
		CommonFunctionFactory.bitLength( queryEngine );
		CommonFunctionFactory.octetLength( queryEngine );
		CommonFunctionFactory.ascii( queryEngine );
		CommonFunctionFactory.chr_char( queryEngine );
		CommonFunctionFactory.instr( queryEngine );
		CommonFunctionFactory.substr( queryEngine );
		//also natively supports ANSI-style substring()
		CommonFunctionFactory.position( queryEngine );
		CommonFunctionFactory.nowCurdateCurtime( queryEngine );
		CommonFunctionFactory.insert( queryEngine );
		CommonFunctionFactory.overlay( queryEngine );
		CommonFunctionFactory.median( queryEngine );
		CommonFunctionFactory.stddevPopSamp( queryEngine );
		CommonFunctionFactory.varPopSamp( queryEngine );
		CommonFunctionFactory.addMonths( queryEngine );
		CommonFunctionFactory.monthsBetween( queryEngine );

		if ( version >= 200 ) {
			//SYSDATE is similar to LOCALTIMESTAMP but it returns the timestamp when it is called
			CommonFunctionFactory.sysdate( queryEngine );
		}

		// from v. 2.2.0 ROWNUM() is supported in all modes as the equivalent of Oracle ROWNUM
		if ( version > 219 ) {
			CommonFunctionFactory.rownum( queryEngine );
		}
	}

	@Override
	public String castPattern(CastType from, CastType to) {
		if ( from== BOOLEAN
				&& (to== INTEGER || to== LONG)) {
			return "casewhen(?1,1,0)";
		}
		return super.castPattern( from, to );
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, boolean timestamp) {
		StringBuilder pattern = new StringBuilder();
		boolean castTo = !timestamp && !unit.isDateUnit();
		switch (unit) {
			case NANOSECOND:
			case NATIVE:
				pattern.append("timestampadd(sql_tsi_frac_second"); //nanos
				break;
			default:
				pattern.append("dateadd(?1");
		}
		pattern.append(", ?2, ");
		if (castTo) {
			pattern.append("cast(?3 as timestamp)");
		}
		else {
			pattern.append("?3");
		}
		pattern.append(")");
		return pattern.toString();
	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, boolean fromTimestamp, boolean toTimestamp) {
		StringBuilder pattern = new StringBuilder();
		boolean castFrom = !fromTimestamp && !unit.isDateUnit();
		boolean castTo = !toTimestamp && !unit.isDateUnit();
		switch (unit) {
			case NANOSECOND:
			case NATIVE:
				pattern.append("timestampdiff(sql_tsi_frac_second"); //nanos
				break;
			default:
				pattern.append("datediff(?1");
		}
		pattern.append(", ");
		if (castFrom) {
			pattern.append("cast(?2 as timestamp)");
		}
		else {
			pattern.append("?2");
		}
		pattern.append(", ");
		if (castTo) {
			pattern.append("cast(?3 as timestamp)");
		}
		else {
			pattern.append("?3");
		}
		pattern.append(")");
		return pattern.toString();
	}

	@Override
	public boolean supportsLockTimeouts() {
		return false;
	}

	@Override
	public String getForUpdateString() {
		if ( version >= 200 ) {
			return " for update";
		}
		else {
			return "";
		}
	}

	@Override
	public LimitHandler getLimitHandler() {
		return version < 200 ? LegacyHSQLLimitHandler.INSTANCE
			: version < 250 ? LimitOffsetLimitHandler.INSTANCE
			: OffsetFetchLimitHandler.INSTANCE;
	}

	// Note : HSQLDB actually supports [IF EXISTS] before AND after the <tablename>
	// But as CASCADE has to be AFTER IF EXISTS in case it's after the tablename,
	// We put the IF EXISTS before the tablename to be able to add CASCADE after.
	@Override
	public boolean supportsIfExistsAfterTableName() {
		return false;
	}

	@Override
	public boolean supportsIfExistsBeforeTableName() {
		return true;
	}

	@Override
	public boolean supportsColumnCheck() {
		return version >= 200;
	}

	@Override
	public SequenceSupport getSequenceSupport() {
		return HSQLSequenceSupport.INSTANCE;
	}

	@Override
	public String getQuerySequencesString() {
		// this assumes schema support, which is present in 1.8.0 and later...
		return "select * from information_schema.sequences";
	}

	@Override
	public SequenceInformationExtractor getSequenceInformationExtractor() {
		return SequenceInformationExtractorHSQLDBDatabaseImpl.INSTANCE;
	}

	@Override
	public String getFromDual() {
		return "from (values(0))";
	}

	@Override
	public ViolatedConstraintNameExtracter getViolatedConstraintNameExtracter() {
		return version < 200 ? EXTRACTER_18 : EXTRACTER_20;
	}

	private static final ViolatedConstraintNameExtracter EXTRACTER_18 = new TemplatedViolatedConstraintNameExtracter() {
		@Override
		protected String doExtractConstraintName(SQLException sqle) throws NumberFormatException {

			final int errorCode = JdbcExceptionHelper.extractErrorCode( sqle );

			switch (errorCode) {
				case -8:
					return extractUsingTemplate(
							"Integrity constraint violation ", " table:",
							sqle.getMessage()
					);
				case -9:
					return extractUsingTemplate(
							"Violation of unique index: ", " in statement [",
							sqle.getMessage()
					);
				case -104:
					return extractUsingTemplate(
							"Unique constraint violation: ", " in statement [",
							sqle.getMessage()
					);
				case -177:
					return extractUsingTemplate(
							"Integrity constraint violation - no parent ", " table:",
							sqle.getMessage()
					);
			}
			return null;
		}

	};

	/**
	 * HSQLDB 2.0 messages have changed
	 * messages may be localized - therefore use the common, non-locale element " table: "
	 */
	private static final ViolatedConstraintNameExtracter EXTRACTER_20 = new TemplatedViolatedConstraintNameExtracter() {
		@Override
		protected String doExtractConstraintName(SQLException sqle) throws NumberFormatException {

			final int errorCode = JdbcExceptionHelper.extractErrorCode( sqle );

			switch (errorCode) {
				case -8:
					return extractUsingTemplate(
							"; ", " table: ",
							sqle.getMessage()
					);
				case -9:
					return extractUsingTemplate(
							"; ", " table: ",
							sqle.getMessage()
					);
				case -104:
					return extractUsingTemplate(
							"; ", " table: ",
							sqle.getMessage()
					);
				case -177:
					return extractUsingTemplate(
							"; ", " table: ",
							sqle.getMessage()
					);
			}
			return null;
		}
	};

	@Override
	public String getSelectClauseNullString(int sqlType) {
		String literal;
		switch ( sqlType ) {
			case Types.LONGVARCHAR:
			case Types.VARCHAR:
			case Types.CHAR:
				literal = "cast(null as varchar(100))";
				break;
			case Types.LONGVARBINARY:
			case Types.VARBINARY:
			case Types.BINARY:
				literal = "cast(null as varbinary(100))";
				break;
			case Types.CLOB:
				literal = "cast(null as clob)";
				break;
			case Types.BLOB:
				literal = "cast(null as blob)";
				break;
			case Types.DATE:
				literal = "cast(null as date)";
				break;
			case Types.TIMESTAMP:
			case Types.TIMESTAMP_WITH_TIMEZONE:
				literal = "cast(null as timestamp)";
				break;
			case Types.BOOLEAN:
				literal = "cast(null as boolean)";
				break;
			case Types.BIT:
				literal = "cast(null as bit)";
				break;
			case Types.TIME:
				literal = "cast(null as time)";
				break;
			default:
				literal = "cast(null as int)";
		}
		return literal;
	}

	@Override
	public boolean supportsUnionAll() {
		return true;
	}

	@Override
	public SqmMultiTableMutationStrategy getFallbackSqmMutationStrategy(
			EntityMappingType rootEntityDescriptor,
			RuntimeModelCreationContext runtimeModelCreationContext) {

		// Hibernate uses this information for temporary tables that it uses for its own operations
		// therefore the appropriate strategy is taken with different versions of HSQLDB

		// All versions of HSQLDB support GLOBAL TEMPORARY tables where the table
		// definition is shared by all users but data is private to the session
		// HSQLDB 2.0 also supports session-based LOCAL TEMPORARY tables where
		// the definition and data is private to the session and table declaration
		// can happen in the middle of a transaction

		if ( version < 200 ) {
			return new GlobalTemporaryTableStrategy(
					new IdTable( rootEntityDescriptor, name -> "HT_" + name ),
					() -> new TempIdTableExporter( false, this::getTypeName ),
					// Version 1.8 GLOBAL TEMPORARY table definitions persist beyond the end
					// of the session (by default, data is cleared at commit).
					AfterUseAction.CLEAN,
					runtimeModelCreationContext.getSessionFactory()
			);
		}
		else {
			return new LocalTemporaryTableStrategy(
					// With HSQLDB 2.0, the table name is qualified with MODULE to assist the drop
					// statement (in-case there is a global name beginning with HT_)
					new IdTable( rootEntityDescriptor, name -> "MODULE.HT_" + name ),
					() -> new TempIdTableExporter( true, this::getTypeName ) {
						@Override
						protected String getCreateCommand() {
							return "declare local temporary table";
						}
					},
					AfterUseAction.DROP,
					TempTableDdlTransactionHandling.NONE,
					runtimeModelCreationContext.getSessionFactory()
			);
		}
	}

	// current timestamp support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	/**
	 * HSQLDB 1.8.x requires CALL CURRENT_TIMESTAMP but this should not
	 * be treated as a callable statement. It is equivalent to
	 * "select current_timestamp from dual" in some databases.
	 * HSQLDB 2.0 also supports VALUES CURRENT_TIMESTAMP
	 * <p/>
	 * {@inheritDoc}
	 */
	@Override
	public boolean supportsCurrentTimestampSelection() {
		return true;
	}

	@Override
	public boolean isCurrentTimestampSelectStringCallable() {
		return false;
	}

	@Override
	public String getCurrentTimestampSelectString() {
		return "call current_timestamp";
	}

	/**
	 * For HSQLDB 2.0, this is a copy of the base class implementation.
	 * For HSQLDB 1.8, only READ_UNCOMMITTED is supported.
	 * <p/>
	 * {@inheritDoc}
	 */
	@Override
	public LockingStrategy getLockingStrategy(Lockable lockable, LockMode lockMode) {
		switch (lockMode) {
			case PESSIMISTIC_FORCE_INCREMENT:
				return new PessimisticForceIncrementLockingStrategy(lockable, lockMode);
			case PESSIMISTIC_WRITE:
				return new PessimisticWriteSelectLockingStrategy(lockable, lockMode);
			case PESSIMISTIC_READ:
				return new PessimisticReadSelectLockingStrategy(lockable, lockMode);
			case OPTIMISTIC:
				return new OptimisticLockingStrategy(lockable, lockMode);
			case OPTIMISTIC_FORCE_INCREMENT:
				return new OptimisticForceIncrementLockingStrategy(lockable, lockMode);
		}
		if ( version < 200 ) {
			return new ReadUncommittedLockingStrategy( lockable, lockMode );
		}
		else {
			return new SelectLockingStrategy( lockable, lockMode );
		}
	}

	private static class ReadUncommittedLockingStrategy extends SelectLockingStrategy {
		private ReadUncommittedLockingStrategy(Lockable lockable, LockMode lockMode) {
			super( lockable, lockMode );
		}

		@Override
		public void lock(Object id, Object version, Object object, int timeout, SharedSessionContractImplementor session)
				throws StaleObjectStateException, JDBCException {
			if ( getLockMode().greaterThan( LockMode.READ ) ) {
				LOG.hsqldbSupportsOnlyReadCommittedIsolation();
			}
			super.lock( id, version, object, timeout, session );
		}
	}

	@Override
	public boolean supportsCommentOn() {
		return version >= 200;
	}

	// Overridden informational metadata ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean supportsEmptyInList() {
		return false;
	}

	@Override
	public boolean requiresCastingOfParametersInSelectClause() {
		return true;
	}

	@Override
	public boolean doesReadCommittedCauseWritersToBlockReaders() {
		return version >= 200;
	}

	@Override
	public boolean doesRepeatableReadCauseReadersToBlockWriters() {
		return version >= 200;
	}

	@Override
	public boolean supportsLobValueChangePropogation() {
		return false;
	}

	@Override
	public String toBooleanValueString(boolean bool) {
		return String.valueOf( bool );
	}

	@Override
	public boolean supportsTupleDistinctCounts() {
		// from v. 2.2.9 is added support for COUNT(DISTINCT ...) with multiple arguments
		return version >= 229;
	}

	@Override
	public IdentityColumnSupport getIdentityColumnSupport() {
		return new HSQLIdentityColumnSupport( this.version);
	}

	@Override
	public NameQualifierSupport getNameQualifierSupport() {
		return NameQualifierSupport.SCHEMA;
	}

	@Override
	public boolean supportsNamedParameters(DatabaseMetaData databaseMetaData) {
		return false;
	}

	// Do not drop constraints explicitly, just do this by cascading instead.
	@Override
	public boolean dropConstraints() {
		return false;
	}

	@Override
	public String getCascadeConstraintsString() {
		return " cascade ";
	}

	@Override
	public String translateDatetimeFormat(String format) {
		return OracleDialect.datetimeFormat(format, false)
				.replace("SSSSSS", "FF")
				.replace("SSSSS", "FF")
				.replace("SSSS", "FF")
				.replace("SSS", "FF")
				.replace("SS", "FF")
				.replace("S", "FF")
				.result();
	}

	@Override
	public String translateExtractField(TemporalUnit unit) {
		//TODO: does not support MICROSECOND, but on the
		//      other hand it doesn't support microsecond
		//      precision in timestamps either so who cares?
		switch (unit) {
			case WEEK: return "week_of_year"; //this is the ISO week number, I believe
			default: return unit.toString();
		}
	}

}
