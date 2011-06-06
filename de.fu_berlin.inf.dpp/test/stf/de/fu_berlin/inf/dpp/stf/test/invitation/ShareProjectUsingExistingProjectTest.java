package de.fu_berlin.inf.dpp.stf.test.invitation;

import static de.fu_berlin.inf.dpp.stf.client.tester.SarosTester.ALICE;
import static de.fu_berlin.inf.dpp.stf.client.tester.SarosTester.BOB;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.rmi.RemoteException;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import de.fu_berlin.inf.dpp.stf.client.StfTestCase;
import de.fu_berlin.inf.dpp.stf.client.util.Util;
import de.fu_berlin.inf.dpp.stf.shared.Constants.TypeOfCreateProject;
import de.fu_berlin.inf.dpp.stf.test.Constants;

public class ShareProjectUsingExistingProjectTest extends StfTestCase {

    /**
     * Preconditions:
     * <ol>
     * <li>Alice (Host, Write Access)</li>
     * <li>Bob (Read-Only Access)</li>
     * </ol>
     * 
     * @throws RemoteException
     */

    @BeforeClass
    public static void runBeforeClass() throws RemoteException {
        initTesters(ALICE, BOB);
        setUpWorkbench();
        setUpSaros();
    }

    @Before
    public void runBeforeEveryTest() throws RemoteException {
        ALICE
            .superBot()
            .views()
            .packageExplorerView()
            .tree()
            .newC()
            .javaProjectWithClasses(Constants.PROJECT1, Constants.PKG1,
                Constants.CLS1);
        BOB.superBot()
            .views()
            .packageExplorerView()
            .tree()
            .newC()
            .javaProjectWithClasses(Constants.PROJECT1, Constants.PKG1,
                Constants.CLS2);
    }

    @After
    public void runAfterEveryTest() throws RemoteException {
        leaveSessionHostFirst(ALICE);
        deleteAllProjectsByActiveTesters();

    }

    @Test
    public void shareProjectUsingExistingProject() throws RemoteException {
        assertFalse(BOB.superBot().views().packageExplorerView()
            .selectPkg(Constants.PROJECT1, Constants.PKG1)
            .existsWithRegex(Constants.CLS1_SUFFIX));
        assertTrue(BOB.superBot().views().packageExplorerView()
            .selectPkg(Constants.PROJECT1, Constants.PKG1)
            .existsWithRegex(Constants.CLS2));
        Util.buildSessionSequentially(Constants.PROJECT1,
            TypeOfCreateProject.EXIST_PROJECT, ALICE, BOB);
        BOB.superBot()
            .views()
            .packageExplorerView()
            .waitUntilClassExists(Constants.PROJECT1, Constants.PKG1,
                Constants.CLS1);
        assertTrue(BOB.superBot().views().packageExplorerView()
            .selectPkg(Constants.PROJECT1, Constants.PKG1)
            .existsWithRegex(Constants.CLS1));
        assertFalse(BOB.superBot().views().packageExplorerView()
            .selectPkg(Constants.PROJECT1, Constants.PKG1)
            .existsWithRegex(Constants.CLS2));
    }

    @Test
    public void shareProjectUsingExistProjectWithCopyAfterCancelLocalChange()
        throws RemoteException {
        assertFalse(BOB.superBot().views().packageExplorerView()
            .selectPkg(Constants.PROJECT1, Constants.PKG1)
            .existsWithRegex(Constants.CLS1_SUFFIX));
        assertTrue(BOB.superBot().views().packageExplorerView()
            .selectPkg(Constants.PROJECT1, Constants.PKG1)
            .existsWithRegex(Constants.CLS2));

        Util.buildSessionSequentially(
            Constants.PROJECT1,
            TypeOfCreateProject.EXIST_PROJECT_WITH_COPY_AFTER_CANCEL_LOCAL_CHANGE,
            ALICE, BOB);
        // assertTrue(BOB.sarosC.isWIndowSessionInvitationActive());
        // BOB.sarosC
        // .confirmProjectSharingWizardUsingExistProjectWithCopy(PROJECT1);

        assertTrue(BOB.superBot().views().packageExplorerView().tree()
            .existsWithRegex(Constants.PROJECT1));
        assertTrue(BOB.superBot().views().packageExplorerView()
            .selectPkg(Constants.PROJECT1, Constants.PKG1)
            .existsWithRegex(Constants.CLS2));
        assertTrue(BOB.superBot().views().packageExplorerView().tree()
            .existsWithRegex(Constants.PROJECT1_NEXT));
        assertTrue(BOB.superBot().views().packageExplorerView()
            .selectPkg(Constants.PROJECT1_NEXT, Constants.PKG1)
            .existsWithRegex(Constants.CLS1));
        // BOB.superBot().views().packageExplorerView()
        // .selectJavaProject(PROJECT1_NEXT).delete();
    }

    @Test
    public void testShareProjectUsingExistingProjectWithCopy()
        throws RemoteException {
        Util.buildSessionSequentially(Constants.PROJECT1,
            TypeOfCreateProject.EXIST_PROJECT_WITH_COPY, ALICE, BOB);
        assertTrue(BOB.superBot().views().packageExplorerView().tree()
            .existsWithRegex(Constants.PROJECT1));
        assertTrue(BOB.superBot().views().packageExplorerView()
            .selectPkg(Constants.PROJECT1, Constants.PKG1)
            .existsWithRegex(Constants.CLS2));
        assertTrue(BOB.superBot().views().packageExplorerView().tree()
            .existsWithRegex(Constants.PROJECT1_NEXT));
        assertTrue(BOB.superBot().views().packageExplorerView()
            .selectPkg(Constants.PROJECT1_NEXT, Constants.PKG1)
            .existsWithRegex(Constants.CLS1));
        // BOB.superBot().views().packageExplorerView()
        // .selectJavaProject(PROJECT1_NEXT).delete();

    }
}
