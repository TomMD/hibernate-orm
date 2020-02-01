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
import org.hibernate.dialect.lock.*;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.TimesTenLimitHandler;
import org.hibernate.dialect.sequence.SequenceSupport;
import org.hibernate.dialect.sequence.TimesTenSequenceSupport;
import org.hibernate.metamodel.mapping.EntityMappingType;
import org.hibernate.metamodel.spi.RuntimeModelCreationContext;
import org.hibernate.persister.entity.Lockable;
import org.hibernate.query.TemporalUnit;
import org.hibernate.query.spi.QueryEngine;
import org.hibernate.query.sqm.mutation.internal.idtable.AfterUseAction;
import org.hibernate.query.sqm.mutation.internal.idtable.GlobalTemporaryTableStrategy;
import org.hibernate.query.sqm.mutation.internal.idtable.IdTable;
import org.hibernate.query.sqm.mutation.internal.idtable.TempIdTableExporter;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableMutationStrategy;
import org.hibernate.tool.schema.extract.internal.SequenceInformationExtractorTimesTenDatabaseImpl;
import org.hibernate.tool.schema.extract.spi.SequenceInformationExtractor;
import org.hibernate.type.StandardBasicTypes;

import java.sql.Types;

/**
 * A SQL dialect for TimesTen 5.1.
 * <p/>
 * Known limitations:
 * joined-subclass support because of no CASE support in TimesTen
 * No support for subqueries that includes aggregation
 * - size() in HQL not supported
 * - user queries that does subqueries with aggregation
 * No CLOB/BLOB support
 * No cascade delete support.
 * No Calendar support
 * No support for updating primary keys.
 *
 * @author Sherry Listgarten, Max Andersen, Chris Jenkins
 */
@SuppressWarnings("deprecation")
public class TimesTenDialect extends Dialect {

	public TimesTenDialect() {
		super();

		//Note: these are the correct type mappings
		//      for the default Oracle type mode
		//      TypeMode=0
		registerColumnType( Types.BIT, 1, "tt_tinyint" );
		registerColumnType( Types.BIT, "tt_tinyint" );
		registerColumnType( Types.BOOLEAN, "tt_tinyint" );

		registerColumnType(Types.TINYINT, "tt_tinyint");
		registerColumnType(Types.SMALLINT, "tt_smallint");
		registerColumnType(Types.INTEGER, "tt_integer");
		registerColumnType(Types.BIGINT, "tt_bigint");

		//note that 'binary_float'/'binary_double' might
		//be better mappings for Java Float/Double

		//'numeric'/'decimal' are synonyms for 'number'
		registerColumnType(Types.NUMERIC, "number($p,$s)");
		registerColumnType(Types.DECIMAL, "number($p,$s)" );

		registerColumnType( Types.VARCHAR, "varchar2($l)" );
		registerColumnType( Types.NVARCHAR, "nvarchar2($l)" );

		//do not use 'date' because it's a datetime
		registerColumnType(Types.DATE, "tt_date");
		//'time' and 'tt_time' are synonyms
		registerColumnType(Types.TIME, "tt_time");
		//`timestamp` has more precision than `tt_timestamp`
//		registerColumnType(Types.TIMESTAMP, "tt_timestamp");
		registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "timestamp($p)");

		getDefaultProperties().setProperty( Environment.USE_STREAMS_FOR_BINARY, "true" );
		getDefaultProperties().setProperty( Environment.STATEMENT_BATCH_SIZE, DEFAULT_BATCH_SIZE );
	}

	@Override
	public int getPreferredSqlTypeCodeForBoolean() {
		return Types.BIT;
	}

	@Override
	public int getDefaultDecimalPrecision() {
		//the maximum
		return 40;
	}

	@Override
	public void initializeFunctionRegistry(QueryEngine queryEngine) {
		super.initializeFunctionRegistry( queryEngine );

		CommonFunctionFactory.trim2( queryEngine );
		CommonFunctionFactory.soundex( queryEngine );
		CommonFunctionFactory.trunc( queryEngine );
		CommonFunctionFactory.toCharNumberDateTimestamp( queryEngine );
		CommonFunctionFactory.ceiling_ceil( queryEngine );
		CommonFunctionFactory.instr( queryEngine );
		CommonFunctionFactory.substr( queryEngine );
		CommonFunctionFactory.substring_substr( queryEngine );
		CommonFunctionFactory.leftRight_substr( queryEngine );
		CommonFunctionFactory.char_chr( queryEngine );
		CommonFunctionFactory.rownumRowid( queryEngine );
		CommonFunctionFactory.sysdate( queryEngine );
		CommonFunctionFactory.addMonths( queryEngine );
		CommonFunctionFactory.monthsBetween( queryEngine );

		queryEngine.getSqmFunctionRegistry().registerBinaryTernaryPattern(
				"locate",
				StandardBasicTypes.INTEGER,
				"instr(?2, ?1)",
				"instr(?2, ?1, ?3)"
		).setArgumentListSignature("(pattern, string[, start])");
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, boolean timestamp) {
		switch (unit) {
			case NANOSECOND:
			case NATIVE:
				return "timestampadd(sql_tsi_frac_second, ?2, ?3)";
			default:
				return "timestampadd(sql_tsi_?1, ?2, ?3)";
		}
	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, boolean fromTimestamp, boolean toTimestamp) {
		switch (unit) {
			case NANOSECOND:
			case NATIVE:
				return "timestampdiff(sql_tsi_frac_second, ?2, ?3)";
			default:
				return "timestampdiff(sql_tsi_?1, ?2, ?3)";
		}
	}

	@Override
	public boolean qualifyIndexName() {
		return false;
	}

	@Override
	public String getAddColumnString() {
		return "add";
	}

	@Override
	public SequenceSupport getSequenceSupport() {
		return TimesTenSequenceSupport.INSTANCE;
	}

	@Override
	public String getQuerySequencesString() {
		return "select name from sys.sequences";
	}

	@Override
	public SequenceInformationExtractor getSequenceInformationExtractor() {
		return SequenceInformationExtractorTimesTenDatabaseImpl.INSTANCE;
	}

	@Override
	public String getCrossJoinSeparator() {
		return ", ";
	}

	@Override
	public boolean supportsNoWait() {
		return true;
	}

	@Override
	public String getForUpdateNowaitString() {
		return " for update nowait";
	}

	@Override
	public boolean supportsColumnCheck() {
		return false;
	}

	@Override
	public boolean supportsTableCheck() {
		return false;
	}

	@Override
	public LimitHandler getLimitHandler() {
		return TimesTenLimitHandler.INSTANCE;
	}

	@Override
	public boolean supportsCurrentTimestampSelection() {
		return true;
	}

	@Override
	public String getCurrentTimestampSelectString() {
		return "select sysdate from sys.dual";
	}

	@Override
	public boolean isCurrentTimestampSelectStringCallable() {
		return false;
	}

	@Override
	public SqmMultiTableMutationStrategy getFallbackSqmMutationStrategy(
			EntityMappingType rootEntityDescriptor,
			RuntimeModelCreationContext runtimeModelCreationContext) {
		return new GlobalTemporaryTableStrategy(
				new IdTable( rootEntityDescriptor,
						name -> name.length() > 30 ? name.substring( 0, 30 ) : name ),
				() -> new TempIdTableExporter( false, this::getTypeName ) {
					@Override
					protected String getCreateOptions() {
						return "on commit delete rows";
					}
				},
				AfterUseAction.CLEAN,
				runtimeModelCreationContext.getSessionFactory()
		);
	}

	@Override
	public LockingStrategy getLockingStrategy(Lockable lockable, LockMode lockMode) {
		// TimesTen has no known variation of a "SELECT ... FOR UPDATE" syntax...
		switch ( lockMode ) {
			case OPTIMISTIC:
				return new OptimisticLockingStrategy( lockable, lockMode );
			case OPTIMISTIC_FORCE_INCREMENT:
				return new OptimisticForceIncrementLockingStrategy( lockable, lockMode );
			case PESSIMISTIC_READ:
				return new PessimisticReadUpdateLockingStrategy( lockable, lockMode );
			case PESSIMISTIC_WRITE:
				return new PessimisticWriteUpdateLockingStrategy( lockable, lockMode );
			case PESSIMISTIC_FORCE_INCREMENT:
				return new PessimisticForceIncrementLockingStrategy( lockable, lockMode );
		}
		if ( lockMode.greaterThan( LockMode.READ ) ) {
			return new UpdateLockingStrategy( lockable, lockMode );
		}
		else {
			return new SelectLockingStrategy( lockable, lockMode );
		}
	}

	@Override
	public boolean supportsUnionAll() {
		return true;
	}

	@Override
	public boolean supportsCircularCascadeDeleteConstraints() {
		return false;
	}

	@Override
	public boolean supportsUniqueConstraintInCreateAlterTable() {
		return false;
	}

	@Override
	public String getSelectClauseNullString(int sqlType) {
		switch (sqlType) {
			case Types.VARCHAR:
			case Types.CHAR:
				return "to_char(null)";

			case Types.DATE:
			case Types.TIME:
			case Types.TIMESTAMP:
			case Types.TIMESTAMP_WITH_TIMEZONE:
				return "to_date(null)";

			default:
				return "to_number(null)";
		}
	}

}
