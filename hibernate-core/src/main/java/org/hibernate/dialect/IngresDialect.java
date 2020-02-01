/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.dialect;

import org.hibernate.cfg.Environment;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.dialect.identity.Ingres10IdentityColumnSupport;
import org.hibernate.dialect.identity.Ingres9IdentityColumnSupport;
import org.hibernate.dialect.pagination.FirstLimitHandler;
import org.hibernate.dialect.pagination.IngresLimitHandler;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.sequence.ANSISequenceSupport;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.query.TemporalUnit;
import org.hibernate.query.spi.QueryEngine;
import org.hibernate.query.sqm.mutation.internal.idtable.AfterUseAction;
import org.hibernate.query.sqm.mutation.internal.idtable.GlobalTemporaryTableStrategy;
import org.hibernate.query.sqm.mutation.internal.idtable.IdTable;
import org.hibernate.query.sqm.mutation.internal.idtable.TempIdTableExporter;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategy;
import org.hibernate.tool.schema.extract.internal.SequenceNameExtractorImpl;
import org.hibernate.tool.schema.extract.spi.SequenceInformationExtractor;
import org.hibernate.type.StandardBasicTypes;

import java.sql.Types;

/**
 * An SQL dialect for Ingres 9.2.
 * <p/>
 * Known limitations: <ul>
 *     <li>
 *         Only supports simple constants or columns on the left side of an IN,
 *         making {@code (1,2,3) in (...)} or {@code (subselect) in (...)} non-supported.
 *     </li>
 *     <li>
 *         Supports only 39 digits in decimal.
 *     </li>
 *     <li>
 *         Explicitly set USE_GET_GENERATED_KEYS property to false.
 *     </li>
 *     <li>
 *         Perform string casts to varchar; removes space padding.
 *     </li>
 * </ul>
 * 
 * @author Ian Booth
 * @author Bruce Lunsford
 * @author Max Rydahl Andersen
 * @author Raymond Fan
 */
public class IngresDialect extends Dialect {

	private final LimitHandler limitHandler;

	private final int version;

	private final SequenceSupport sequenceSupport;

	int getVersion() {
		return version;
	}

	public IngresDialect(DialectResolutionInfo info) {
		this( info.getDatabaseMajorVersion() * 100 + info.getDatabaseMinorVersion() * 10 );
	}

	public IngresDialect() {
		this(920);
	}

	/**
	 * Constructs a IngresDialect
	 */
	public IngresDialect(int version) {
		super();
		this.version = version;

		if ( getVersion() < 1000 ) {
			registerColumnType( Types.BIT, 1, "tinyint" );
			registerColumnType( Types.BOOLEAN, "tinyint" );
		}
		else {
			registerColumnType( Types.BIT, 1, "boolean" );
			registerColumnType( Types.BOOLEAN, "boolean" );
		}
		registerColumnType( Types.BIT, "tinyint" );

		registerColumnType( Types.NUMERIC, "decimal($p, $s)" ); //Ingres has no 'numeric' type

		final int maxStringLength = 32_000;

		registerColumnType( Types.BINARY, maxStringLength, "byte($l)" );
		registerColumnType( Types.VARBINARY, maxStringLength, "varbyte($l)" );
		//note: 'long byte' is a  synonym for 'blob'
		registerColumnType( Types.VARBINARY, "long byte($l)" );

		//TODO: should we be using nchar/nvarchar/long nvarchar
		//      here? I think Ingres char/varchar types don't
		//      support Unicode. Copy what AbstractHANADialect
		//      does with a Hibernate property to config this.
		registerColumnType( Types.CHAR, maxStringLength, "char($l)" );
		registerColumnType( Types.VARCHAR, maxStringLength, "varchar($l)" );
		//note: 'long varchar' is a synonym for 'clob'
		registerColumnType( Types.VARCHAR, "long varchar($l)" );

		registerColumnType( Types.NCHAR, maxStringLength, "nchar($l)" );
		registerColumnType( Types.NVARCHAR, maxStringLength, "nvarchar($l)" );
		//note: 'long nvarchar' is a synonym for 'nclob'
		registerColumnType( Types.NVARCHAR, "long nvarchar($l)" );

		if ( getVersion() >= 930 ) {
			// Not completely necessary, given that Ingres
			// can be configured to set DATE = ANSIDATE
			registerColumnType( Types.DATE, "ansidate" );
		}

		// Ingres driver supports getGeneratedKeys but only in the following
		// form:
		// The Ingres DBMS returns only a single table key or a single object
		// key per insert statement. Ingres does not return table and object
		// keys for INSERT AS SELECT statements. Depending on the keys that are
		// produced by the statement executed, auto-generated key parameters in
		// execute(), executeUpdate(), and prepareStatement() methods are
		// ignored and getGeneratedKeys() returns a result-set containing no
		// rows, a single row with one column, or a single row with two columns.
		// Ingres JDBC Driver returns table and object keys as BINARY values.
		getDefaultProperties().setProperty( Environment.USE_GET_GENERATED_KEYS, "false" );

		if ( getVersion() < 1000 ) {
			// There is no support for a native boolean type that accepts values
			// of true, false or unknown. Using the tinyint type requires
			// substitutions of true and false.
			getDefaultProperties().setProperty( Environment.QUERY_SUBSTITUTIONS, "true=1,false=0" );
		}

		limitHandler = getVersion() < 930 ? FirstLimitHandler.INSTANCE : IngresLimitHandler.INSTANCE;

		sequenceSupport = new ANSISequenceSupport() {
			@Override
			public boolean supportsPooledSequences() {
				return getVersion() >= 930;
			}
		};
	}

	@Override
	public int getPreferredSqlTypeCodeForBoolean() {
		return getVersion() < 1000 ? Types.BIT : Types.BOOLEAN;
	}

	@Override
	public String toBooleanValueString(boolean bool) {
		return getVersion() < 1000
				? super.toBooleanValueString( bool )
				: String.valueOf( bool );
	}


	@Override
	public int getDefaultDecimalPrecision() {
		//the maximum
		return 39;
	}

	@Override
	public void initializeFunctionRegistry(QueryEngine queryEngine) {
		super.initializeFunctionRegistry( queryEngine );

		// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
		// Common functions

		CommonFunctionFactory.log( queryEngine );
		CommonFunctionFactory.rand( queryEngine );
		CommonFunctionFactory.soundex( queryEngine );
		CommonFunctionFactory.octetLength( queryEngine );
		CommonFunctionFactory.repeat( queryEngine );
		CommonFunctionFactory.trim2( queryEngine );
		CommonFunctionFactory.trunc( queryEngine );
		CommonFunctionFactory.truncate( queryEngine );
		CommonFunctionFactory.initcap( queryEngine );
		CommonFunctionFactory.yearMonthDay( queryEngine );
		CommonFunctionFactory.hourMinuteSecond( queryEngine );
		CommonFunctionFactory.dayofweekmonthyear( queryEngine );
		CommonFunctionFactory.weekQuarter( queryEngine );
		CommonFunctionFactory.lastDay( queryEngine );
		CommonFunctionFactory.concat_pipeOperator( queryEngine );
		CommonFunctionFactory.substr( queryEngine );
		CommonFunctionFactory.monthsBetween( queryEngine );
		CommonFunctionFactory.substring_substr( queryEngine );
		//also natively supports ANSI-style substring()
		CommonFunctionFactory.ascii( queryEngine );
		CommonFunctionFactory.char_chr( queryEngine );
		CommonFunctionFactory.sysdate( queryEngine );
		CommonFunctionFactory.position( queryEngine );
		CommonFunctionFactory.format_dateFormat( queryEngine );
		CommonFunctionFactory.dateTrunc( queryEngine );

		queryEngine.getSqmFunctionRegistry().registerBinaryTernaryPattern(
				"locate",
				StandardBasicTypes.INTEGER,
				"position(?1 in ?2)",
				"(position(?1 in substring(?2 from ?3)) + (?3) - 1)"
		).setArgumentListSignature("(pattern, string[, start])");

		queryEngine.getSqmFunctionRegistry().registerPattern( "extract", "date_part('?1', ?2)", StandardBasicTypes.INTEGER );

		CommonFunctionFactory.bitandorxornot_bitAndOrXorNot(queryEngine);

		queryEngine.getSqmFunctionRegistry().namedDescriptorBuilder( "squeeze" )
				.setExactArgumentCount( 1 )
				.setInvariantType( StandardBasicTypes.STRING )
				.register();

	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, boolean timestamp) {
		return "timestampadd(?1, ?2, ?3)";

	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, boolean fromTimestamp, boolean toTimestamp) {
		return "timestampdiff(?1, ?2, ?3)";
	}

	@Override
	public String getSelectGUIDString() {
		return "select uuid_to_char(uuid_create())";
	}

	@Override
	public boolean dropConstraints() {
		return false;
	}

	@Override
	public String getNullColumnString() {
		return " with null";
	}

	// SEQUENCE support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~


	@Override
	public SequenceSupport getSequenceSupport() {
		return sequenceSupport;
	}

	@Override
	public String getQuerySequencesString() {
		return getVersion() < 930
				? "select seq_name from iisequence"
				: "select seq_name from iisequences";
	}

	@Override
	public SequenceInformationExtractor getSequenceInformationExtractor() {
		return SequenceNameExtractorImpl.INSTANCE;
	}

	@Override
	public String getLowercaseFunction() {
		return "lowercase";
	}

	@Override
	public LimitHandler getLimitHandler() {
		return limitHandler;
	}

	@Override
	public IdentityColumnSupport getIdentityColumnSupport() {
		if ( getVersion() >= 1000 ) {
			return new Ingres10IdentityColumnSupport();
		}
		else if (getVersion() >= 930) {
			return new Ingres9IdentityColumnSupport();
		}
		else {
			return super.getIdentityColumnSupport();
		}
	}

	// lock acquisition support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean supportsOuterJoinForUpdate() {
		return getVersion() >= 930;
	}

	@Override
	public boolean forUpdateOfColumns() {
		return getVersion() >= 930;
	}

	// current timestamp support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean isCurrentTimestampSelectStringCallable() {
		return false;
	}

	@Override
	public boolean supportsCurrentTimestampSelection() {
		return getVersion() >= 930;
	}

	@Override
	public SqmMultiTableMutationStrategy getFallbackSqmMutationStrategy(
			EntityMappingType rootEntityDescriptor,
			RuntimeModelCreationContext runtimeModelCreationContext) {
		return new GlobalTemporaryTableStrategy(
				new IdTable( rootEntityDescriptor, name -> "session." + name ),
				() -> new TempIdTableExporter( false, this::getTypeName ) {
					@Override
					protected String getCreateOptions() {
						return "on commit preserve rows with norecovery";
					}

					@Override
					protected String getCreateCommand() {
						return "declare global temporary table";
					}
				},
				AfterUseAction.CLEAN,
				runtimeModelCreationContext.getSessionFactory()
		);
	}

	@Override
	@SuppressWarnings("deprecation")
	public String getCurrentTimestampSQLFunctionName() {
		return getVersion() >= 930 ? "current_timestamp" : "date(now)";
	}

	// union subclass support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean supportsUnionAll() {
		return getVersion() >= 930;
	}

	// Informational metadata ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean doesReadCommittedCauseWritersToBlockReaders() {
		return getVersion() >= 930;
	}

	@Override
	public boolean doesRepeatableReadCauseReadersToBlockWriters() {
		return getVersion() >= 930;
	}

	// limit/offset support ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public String getFromDual() {
		//this is only necessary if the query has a where clause
		return "from (select 0) as dual";
	}

	// Overridden informational metadata ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	@Override
	public boolean supportsSubselectAsInPredicateLHS() {
		return false;
	}

	@Override
	public boolean supportsEmptyInList() {
		return false;
	}

	@Override
	public boolean supportsExpectedLobUsagePattern() {
		return false;
	}

	@Override
	public boolean supportsTupleDistinctCounts() {
		return false;
	}

	@Override
	public String translateDatetimeFormat(String format) {
		return MySQLDialect.datetimeFormat( format ).result();
	}

	@Override
	public String translateExtractField(TemporalUnit unit) {
		switch ( unit ) {
			case DAY_OF_MONTH: return "day";
			case DAY_OF_YEAR: return "doy";
			case DAY_OF_WEEK: return "dow";
			case WEEK: return "iso_week";
			default: return super.translateExtractField( unit );
		}
	}

}
