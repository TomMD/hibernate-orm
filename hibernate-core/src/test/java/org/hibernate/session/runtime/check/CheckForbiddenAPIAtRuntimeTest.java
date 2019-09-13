/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.session.runtime.check;

import static org.hibernate.testing.transaction.TransactionUtil.doInHibernate;

import org.hibernate.testing.TestForIssue;
import org.hibernate.testing.junit4.BaseCoreFunctionalTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;

/**
 * @author Fabio Massimo Ercoli
 */
@TestForIssue(jiraKey = "HHH-13604")
@RunWith(BMUnitRunner.class)
// It seems to be necessary to specify the Gradle target resource test directory.
// See https://developer.jboss.org/wiki/BMUnitUsingBytemanWithJUnitOrTestNGFromMavenAndAnt. They did the same for Maven.
// This is ugly, since it makes the test not working from an IDE.
@BMUnitConfig(loadDirectory = "target/resources/test/org/hibernate/session/runtime/check", debug = true)
@BMScript(value = "check.btm")
public class CheckForbiddenAPIAtRuntimeTest extends BaseCoreFunctionalTestCase {

	@Override
	protected Class<?>[] getAnnotatedClasses() {
		return new Class[] { Book.class };
	}

	@Test
	public void smoke() {
		Book book = new Book();
		book.setId( 1 );
		book.setIsbn( "777-33-99999-11-7" );
		book.setTitle( "Songs of Innocence and Experience" );
		book.setAuthor( "William Blake" );
		book.setCopies( 1_000_000 );

		doInHibernate( this::sessionFactory, session -> {
			session.persist( book );
		} );

		// TODO the following code would trigger the Byteman rule:
		//  "check regex pattern parsing is not used at runtime"
		/*doInHibernate( this::sessionFactory, session -> {
			Book loaded = session.load( Book.class, 1 );
			assertEquals( book, loaded );

			loaded.setCopies( 999_999 );
		} );

		doInHibernate( this::sessionFactory, session -> {
			Book loaded = session.load( Book.class, 1 );
			assertEquals( Integer.valueOf( 999_999 ), loaded.getCopies() );

			session.remove( loaded );
		} );

		doInHibernate( this::sessionFactory, session -> {
			Query<Book> query = session.createQuery( "select b from Book b", Book.class );
			List<Book> books = query.getResultList();

			assertEquals( 0, books.size() );
		} );*/
	}

	@Before
	public void before() {
		buildSessionFactory();
	}

	@After
	public void after() {
		releaseSessionFactory();
	}

	@Override
	public void releaseTransactions() {
		// The super class method would trigger the Byteman rule:
		// "check regex pattern parsing is not used at runtime"
	}
}
