/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.dialect;

import org.hibernate.LockMode;
import org.hibernate.cfg.Environment;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.dialect.identity.CacheIdentityColumnSupport;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.lock.*;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.TopLimitHandler;
import org.hibernate.dialect.sequence.CacheSequenceSupport;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.exception.internal.CacheSQLExceptionConversionDelegate;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor;
import org.hibernate.exception.spi.ViolatedConstraintNameExtractor;
import org.hibernate.persister.entity.Lockable;
import org.hibernate.query.TemporalUnit;
import org.hibernate.query.spi.QueryEngine;
import org.hibernate.sql.CacheJoinFragment;
import org.hibernate.sql.JoinFragment;
import org.hibernate.type.StandardBasicTypes;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor.extractUsingTemplate;

/**
 * Dialect for Intersystems Cach&eacute; SQL 2007.1 and above.
 *
 * @author Jonathan Levinson
 */
public class CacheDialect extends Dialect {

	public CacheDialect() {
		super();
		// Note: For object <-> SQL datatype mappings see:
		// Configuration Manager > Advanced > SQL > System DDL Datatype Mappings

		registerColumnType( Types.BOOLEAN, "bit" );

		//no explicit precision
		registerColumnType(Types.TIMESTAMP, "timestamp");
		registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "timestamp");

		registerColumnType( Types.BLOB, "image" );
		registerColumnType( Types.CLOB, "text" );

		getDefaultProperties().setProperty( Environment.USE_STREAMS_FOR_BINARY, "false" );
		getDefaultProperties().setProperty( Environment.STATEMENT_BATCH_SIZE, DEFAULT_BATCH_SIZE );

		getDefaultProperties().setProperty( Environment.USE_SQL_COMMENTS, "false" );
	}

	private static void useJdbcEscape(QueryEngine queryEngine, String name) {
		//Yep, this seems to be truly necessary for certain functions
		queryEngine.getSqmFunctionRegistry().wrapInJdbcEscape(
				name,
				queryEngine.getSqmFunctionRegistry().findFunctionDescriptor(name)
		);
	}

	@Override
	public int getPreferredSqlTypeCodeForBoolean() {
		return Types.BIT;
	}

	@Override
	public int getDefaultDecimalPrecision() {
		//the largest *meaningful* value
		return 19;
	}

	@Override
	public void initializeFunctionRegistry(QueryEngine queryEngine) {
		super.initializeFunctionRegistry( queryEngine );

		CommonFunctionFactory.repeat( queryEngine );
		CommonFunctionFactory.trim2( queryEngine );
		CommonFunctionFactory.substr( queryEngine );
		//also natively supports ANSI-style substring()
		CommonFunctionFactory.concat_pipeOperator( queryEngine );
		CommonFunctionFactory.cot( queryEngine );
		CommonFunctionFactory.log10( queryEngine );
		CommonFunctionFactory.log( queryEngine );
		CommonFunctionFactory.pi( queryEngine );
		CommonFunctionFactory.space( queryEngine );
		CommonFunctionFactory.hourMinuteSecond( queryEngine );
		CommonFunctionFactory.yearMonthDay( queryEngine );
		CommonFunctionFactory.weekQuarter( queryEngine );
		CommonFunctionFactory.daynameMonthname( queryEngine );
		CommonFunctionFactory.toCharNumberDateTimestamp( queryEngine );
		CommonFunctionFactory.truncate( queryEngine );
		CommonFunctionFactory.dayofweekmonthyear( queryEngine );
		CommonFunctionFactory.repeat_replicate( queryEngine );
		CommonFunctionFactory.datepartDatename( queryEngine );
		CommonFunctionFactory.ascii( queryEngine );
		CommonFunctionFactory.chr_char( queryEngine );
		CommonFunctionFactory.nowCurdateCurtime( queryEngine );
		CommonFunctionFactory.sysdate( queryEngine );
		CommonFunctionFactory.stddev( queryEngine );
		CommonFunctionFactory.stddevPopSamp( queryEngine );
		CommonFunctionFactory.variance( queryEngine );
		CommonFunctionFactory.varPopSamp( queryEngine );
		CommonFunctionFactory.lastDay( queryEngine );

		queryEngine.getSqmFunctionRegistry().registerBinaryTernaryPattern(
				"locate",
				StandardBasicTypes.INTEGER,
				"$find(?2, ?1)",
				"$find(?2, ?1, ?3)"
		).setArgumentListSignature("(pattern, string[, start])");

		useJdbcEscape(queryEngine, "sin");
		useJdbcEscape(queryEngine, "cos");
		useJdbcEscape(queryEngine, "tan");
		useJdbcEscape(queryEngine, "asin");
		useJdbcEscape(queryEngine, "acos");
		useJdbcEscape(queryEngine, "atan");
		useJdbcEscape(queryEngine, "atan2");
		useJdbcEscape(queryEngine, "exp");
		useJdbcEscape(queryEngine, "log");
		useJdbcEscape(queryEngine, "log10");
		useJdbcEscape(queryEngine, "pi");
		useJdbcEscape(queryEngine, "truncate");

		useJdbcEscape(queryEngine, "left");
		useJdbcEscape(queryEngine, "right");

		useJdbcEscape(queryEngine, "hour");
		useJdbcEscape(queryEngine, "minute");
		useJdbcEscape(queryEngine, "second");
		useJdbcEscape(queryEngine, "week");
		useJdbcEscape(queryEngine, "quarter");
		useJdbcEscape(queryEngine, "dayname");
		useJdbcEscape(queryEngine, "monthname");
		useJdbcEscape(queryEngine, "dayofweek");
		useJdbcEscape(queryEngine, "dayofmonth");
		useJdbcEscape(queryEngine, "dayofyear");

	}

	@Override
	public String extractPattern(TemporalUnit unit) {
		return "datepart(?1, ?2)";
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, boolean timestamp) {
		switch (unit) {
			case NANOSECOND:
			case NATIVE:
				return "dateadd(millisecond, (?2)/1e6, ?3)";
			default:
				return "dateadd(?1, ?2, ?3)";
		}
	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, boolean fromTimestamp, boolean toTimestamp) {
		switch (unit) {
			case NANOSECOND:
			case NATIVE:
				return "datediff(millisecond, ?2, ?3)*1e6";
			default:
				return "datediff(?1, ?2, ?3)";
		}
	}

	// DDL support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean qualifyIndexName() {
		// Do we need to qualify index names with the schema name?
		return false;
	}

	@Override
	@SuppressWarnings("StringBufferReplaceableByString")
	public String getAddForeignKeyConstraintString(
			String constraintName,
			String[] foreignKey,
			String referencedTable,
			String[] primaryKey,
			boolean referencesPrimaryKey) {
		// The syntax used to add a foreign key constraint to a table.
		return new StringBuilder( 300 )
				.append( " ADD CONSTRAINT " )
				.append( constraintName )
				.append( " FOREIGN KEY " )
				.append( constraintName )
				.append( " (" )
				.append( String.join( ", ", foreignKey ) )
				.append( ") REFERENCES " )
				.append( referencedTable )
				.append( " (" )
				.append( String.join( ", ", primaryKey ) )
				.append( ") " )
				.toString();
	}

	@Override
	public boolean hasSelfReferentialForeignKeyBug() {
		return true;
	}


	@Override
	public String getNativeIdentifierGeneratorStrategy() {
		return "identity";
	}

	// IDENTITY support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public IdentityColumnSupport getIdentityColumnSupport() {
		return new CacheIdentityColumnSupport();
	}

	// SEQUENCE support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public SequenceSupport getSequenceSupport() {
		return CacheSequenceSupport.INSTANCE;
	}

	public String getQuerySequencesString() {
		return "select name from InterSystems.Sequences";
	}

	// lock acquisition support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean supportsOuterJoinForUpdate() {
		return false;
	}

	@Override
	public LockingStrategy getLockingStrategy(Lockable lockable, LockMode lockMode) {
		// InterSystems Cache' does not current support "SELECT ... FOR UPDATE" syntax...
		// Set your transaction mode to READ_COMMITTED before using
		switch (lockMode) {
			case PESSIMISTIC_FORCE_INCREMENT:
				return new PessimisticForceIncrementLockingStrategy(lockable, lockMode);
			case PESSIMISTIC_WRITE:
				return new PessimisticWriteUpdateLockingStrategy(lockable, lockMode);
			case PESSIMISTIC_READ:
				return new PessimisticReadUpdateLockingStrategy(lockable, lockMode);
			case OPTIMISTIC:
				return new OptimisticLockingStrategy(lockable, lockMode);
			case OPTIMISTIC_FORCE_INCREMENT:
				return new OptimisticForceIncrementLockingStrategy(lockable, lockMode);
		}
		if ( lockMode.greaterThan( LockMode.READ ) ) {
			return new UpdateLockingStrategy( lockable, lockMode );
		}
		else {
			return new SelectLockingStrategy( lockable, lockMode );
		}
	}

	// LIMIT support (ala TOP) ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public LimitHandler getLimitHandler() {
		return TopLimitHandler.INSTANCE;
	}

	// callable statement support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public int registerResultSetOutParameter(CallableStatement statement, int col) throws SQLException {
		return col;
	}

	@Override
	public ResultSet getResultSet(CallableStatement ps) throws SQLException {
		ps.execute();
		return (ResultSet) ps.getObject( 1 );
	}

	// miscellaneous support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public String getLowercaseFunction() {
		// The name of the SQL function that transforms a string to lowercase
		return "lower";
	}

	@Override
	public String getNullColumnString() {
		// The keyword used to specify a nullable column.
		return " null";
	}

	@Override
	@SuppressWarnings("deprecation")
	public JoinFragment createOuterJoinFragment() {
		// Create an OuterJoinGenerator for this dialect.
		return new CacheJoinFragment();
	}

	@Override
	public String getNoColumnsInsertString() {
		// The keyword used to insert a row without specifying
		// any column values
		return " default values";
	}

	@Override
	public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
		return new CacheSQLExceptionConversionDelegate( this );
	}

	@Override
	public ViolatedConstraintNameExtractor getViolatedConstraintNameExtracter() {
		return EXTRACTOR;
	}

	private static final ViolatedConstraintNameExtractor EXTRACTOR =
			new TemplatedViolatedConstraintNameExtractor( sqle ->
					extractUsingTemplate( "constraint (", ") violated", sqle.getMessage() )
			);


	// Overridden informational metadata ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean supportsEmptyInList() {
		return false;
	}

	@Override
	public boolean areStringComparisonsCaseInsensitive() {
		return true;
	}

	@Override
	public boolean supportsResultSetPositionQueryMethodsOnForwardOnlyCursor() {
		return false;
	}

	@Override
	public String translateDatetimeFormat(String format) {
		//I don't think Cache needs FM
		return OracleDialect.datetimeFormat( format, false ).result();
	}

}
