/*
 * *************************************************************************************
 *  Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 *  http://esper.codehaus.org                                                          *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 * *************************************************************************************
 */

package com.espertech.esper.regression.context;

import com.espertech.esper.client.*;
import com.espertech.esper.client.context.*;
import com.espertech.esper.client.scopetest.EPAssertionUtil;
import com.espertech.esper.client.scopetest.SupportUpdateListener;
import com.espertech.esper.client.soda.EPStatementObjectModel;
import com.espertech.esper.core.service.EPServiceProviderSPI;
import com.espertech.esper.core.service.EPStatementSPI;
import com.espertech.esper.filter.FilterServiceSPI;
import com.espertech.esper.support.bean.SupportBean;
import com.espertech.esper.support.bean.SupportBean_S0;
import com.espertech.esper.support.client.SupportConfigFactory;
import com.espertech.esper.support.util.AgentInstanceAssertionUtil;
import com.espertech.esper.util.EventRepresentationEnum;
import junit.framework.TestCase;

import java.util.*;
import java.util.zip.CRC32;

public class TestContextHashSegmented extends TestCase {

    private EPServiceProvider epService;
    private SupportUpdateListener listener;
    private EPServiceProviderSPI spi;

    public void setUp()
    {
        Configuration configuration = SupportConfigFactory.getConfiguration();
        configuration.addEventType("SupportBean", SupportBean.class);
        configuration.addEventType("SupportBean_S0", SupportBean_S0.class);
        configuration.getEngineDefaults().getLogging().setEnableExecutionDebug(true);
        epService = EPServiceProviderManager.getDefaultProvider(configuration);
        epService.initialize();
        spi = (EPServiceProviderSPI) epService;

        listener = new SupportUpdateListener();
    }

    public void tearDown() {
        listener = null;
    }

    public void testScoringUseCase() throws Exception {
        runAssertionScoringUseCase(EventRepresentationEnum.OBJECTARRAY);
        runAssertionScoringUseCase(EventRepresentationEnum.MAP);
        runAssertionScoringUseCase(EventRepresentationEnum.DEFAULT);
    }

    private void runAssertionScoringUseCase(EventRepresentationEnum eventRepresentationEnum) throws Exception {
        String[] fields = "userId,keyword,sumScore".split(",");
        String epl =
                eventRepresentationEnum.getAnnotationText() + " create schema ScoreCycle (userId string, keyword string, productId string, score long);\n" +
                eventRepresentationEnum.getAnnotationText() + " create schema UserKeywordTotalStream (userId string, keyword string, sumScore long);\n" +
                "\n" +
                eventRepresentationEnum.getAnnotationText() + " create context HashByUserCtx as " +
                        "coalesce by consistent_hash_crc32(userId) from ScoreCycle, " +
                        "consistent_hash_crc32(userId) from UserKeywordTotalStream " +
                        "granularity 1000000;\n" +
                "\n" +
                "context HashByUserCtx create window ScoreCycleWindow.std:unique(productId, keyword) as ScoreCycle;\n" +
                "\n" +
                "context HashByUserCtx insert into ScoreCycleWindow select * from ScoreCycle;\n" +
                "\n" +
                "@Name('outOne') context HashByUserCtx insert into UserKeywordTotalStream \n" +
                "select userId, keyword, sum(score) as sumScore from ScoreCycleWindow group by keyword;\n" +
                "\n" +
                "@Name('outTwo') context HashByUserCtx on UserKeywordTotalStream(sumScore > 10000) delete from ScoreCycleWindow;\n";

        epService.getEPAdministrator().getDeploymentAdmin().parseDeploy(epl);
        epService.getEPAdministrator().getStatement("outOne").addListener(listener);

        makeSendScoreEvent("ScoreCycle", eventRepresentationEnum, "Pete", "K1", "P1", 100);
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"Pete", "K1", 100L});

        makeSendScoreEvent("ScoreCycle", eventRepresentationEnum, "Pete", "K1", "P2", 15);
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"Pete", "K1", 115L});

        makeSendScoreEvent("ScoreCycle", eventRepresentationEnum, "Joe", "K1", "P2", 30);
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"Joe", "K1", 30L});

        makeSendScoreEvent("ScoreCycle", eventRepresentationEnum, "Joe", "K2", "P1", 40);
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"Joe", "K2", 40L});

        makeSendScoreEvent("ScoreCycle", eventRepresentationEnum, "Joe", "K1", "P1", 20);
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{"Joe", "K1", 50L});

        epService.initialize();
    }

    public void testContextPartitionSelection() {
        String[] fields = "c0,c1,c2".split(",");
        epService.getEPAdministrator().createEPL("create context MyCtx as coalesce consistent_hash_crc32(theString) from SupportBean granularity 16 preallocate");
        EPStatement stmt = epService.getEPAdministrator().createEPL("context MyCtx select context.id as c0, theString as c1, sum(intPrimitive) as c2 from SupportBean.win:keepall() group by theString");

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 1));
        EPAssertionUtil.assertPropsPerRow(stmt.iterator(), stmt.safeIterator(), fields, new Object[][]{{5, "E1", 1}});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 10));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 2));
        epService.getEPRuntime().sendEvent(new SupportBean("E3", 100));
        epService.getEPRuntime().sendEvent(new SupportBean("E3", 101));
        epService.getEPRuntime().sendEvent(new SupportBean("E1", 3));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(), stmt.safeIterator(), fields, new Object[][]{{5, "E1", 6}, {15, "E2", 10}, {9, "E3", 201}});

        // test iterator targeted hash
        MySelectorHash selector = new MySelectorHash(Collections.singleton(15));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(selector), stmt.safeIterator(selector), fields, new Object[][]{{15, "E2", 10}});
        selector = new MySelectorHash(new HashSet<Integer>(Arrays.asList(1, 9, 5)));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(selector), stmt.safeIterator(selector), fields, new Object[][]{{5, "E1", 6}, {9, "E3", 201}});
        assertFalse(stmt.iterator(new MySelectorHash(Collections.singleton(99))).hasNext());
        assertFalse(stmt.iterator(new MySelectorHash(Collections.<Integer>emptySet())).hasNext());
        assertFalse(stmt.iterator(new MySelectorHash(null)).hasNext());

        // test iterator filtered
        MySelectorFilteredHash filtered = new MySelectorFilteredHash(Collections.singleton(15));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(filtered), stmt.safeIterator(filtered), fields, new Object[][]{{15, "E2", 10}});
        filtered = new MySelectorFilteredHash(new HashSet<Integer>(Arrays.asList(1, 9, 5)));
        EPAssertionUtil.assertPropsPerRowAnyOrder(stmt.iterator(filtered), stmt.safeIterator(filtered), fields, new Object[][]{{5, "E1", 6}, {9, "E3", 201}});

        // test always-false filter - compare context partition info
        filtered = new MySelectorFilteredHash(Collections.<Integer>emptySet());
        assertFalse(stmt.iterator(filtered).hasNext());
        assertEquals(16, filtered.getContexts().size());

        try {
            stmt.iterator(new ContextPartitionSelectorSegmented() {
                public List<Object[]> getPartitionKeys() {
                    return null;
                }
            });
            fail();
        }
        catch (InvalidContextPartitionSelector ex) {
            assertTrue("message: " + ex.getMessage(), ex.getMessage().startsWith("Invalid context partition selector, expected an implementation class of any of [ContextPartitionSelectorAll, ContextPartitionSelectorFiltered, ContextPartitionSelectorById, ContextPartitionSelectorHash] interfaces but received com."));
        }
    }

    public void testInvalid() {
        String epl;

        // invalid filter spec
        epl = "create context ACtx coalesce hash_code(intPrimitive) from SupportBean(dummy = 1) granularity 10";
        tryInvalid(epl, "Error starting statement: Property named 'dummy' is not valid in any stream [");

        // invalid hash code function
        epl = "create context ACtx coalesce hash_code_xyz(intPrimitive) from SupportBean granularity 10";
        tryInvalid(epl, "Error starting statement: For context 'ACtx' expected a hash function that is any of {consistent_hash_crc32, hash_code} or a plug-in single-row function or script but received 'hash_code_xyz' [");

        // invalid no-param hash code function
        epl = "create context ACtx coalesce hash_code() from SupportBean granularity 10";
        tryInvalid(epl, "Error starting statement: For context 'ACtx' expected one or more parameters to the hash function, but found no parameter list [");

        // validate statement not applicable filters
        epService.getEPAdministrator().createEPL("create context ACtx coalesce hash_code(intPrimitive) from SupportBean granularity 10");
        epl = "context ACtx select * from SupportBean_S0";
        tryInvalid(epl, "Error starting statement: Segmented context 'ACtx' requires that any of the event types that are listed in the segmented context also appear in any of the filter expressions of the statement, type 'SupportBean_S0' is not one of the types listed [");
    }

    private void tryInvalid(String epl, String expected) {
        try {
            epService.getEPAdministrator().createEPL(epl);
            fail();
        }
        catch (EPStatementException ex) {
            if (!ex.getMessage().startsWith(expected)) {
                throw new RuntimeException("Expected/Received:\n" + expected + "\n" + ex.getMessage() + "\n");
            }
            assertTrue(expected.trim().length() != 0);
        }
    }

    public void testHashSegmentedFilter() {

        String ctx = "HashSegmentedContext";
        String eplCtx = "@Name('context') create context " + ctx + " as " +
                "coalesce " +
                " consistent_hash_crc32(theString) from SupportBean(intPrimitive > 10) " +
                "granularity 4 " +
                "preallocate";
        epService.getEPAdministrator().createEPL(eplCtx);
        HashCodeFuncGranularCRC32 codeFunc = new HashCodeFuncGranularCRC32(4);

        String eplStmt = "context " + ctx + " " + "select context.name as c0, intPrimitive as c1 from SupportBean";
        EPStatementSPI statement = (EPStatementSPI) epService.getEPAdministrator().createEPL(eplStmt);
        statement.addListener(listener);

        String[] fields = "c0,c1".split(",");

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 10));
        epService.getEPRuntime().sendEvent(new SupportBean("E2", 1));
        assertFalse(listener.isInvoked());

        epService.getEPRuntime().sendEvent(new SupportBean("E3", 12));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, 12});
        assertIterator(statement, fields, new Object[][]{{ctx, 12}});

        epService.getEPRuntime().sendEvent(new SupportBean("E4", 10));
        epService.getEPRuntime().sendEvent(new SupportBean("E5", 1));
        assertIterator(statement, fields, new Object[][]{{ctx, 12}});
        assertFalse(listener.isInvoked());

        epService.getEPRuntime().sendEvent(new SupportBean("E6", 15));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, 15});
    }

    public void testHashSegmentedManyArg() {
        tryHash("consistent_hash_crc32(theString, intPrimitive)");
        tryHash("hash_code(theString, intPrimitive)");
    }

    private void tryHash(String hashFunc) {
        String eplCtxCRC32 = "@Name('context') create context Ctx1 as coalesce " +
                hashFunc + " from SupportBean " +
                "granularity 1000000";
        epService.getEPAdministrator().createEPL(eplCtxCRC32);

        String[] fields = "c1,c2,c3,c4,c5".split(",");
        String eplStmt = "context Ctx1 select intPrimitive as c1, " +
                "sum(longPrimitive) as c2, prev(1, longPrimitive) as c3, prior(1, longPrimitive) as c4," +
                "(select p00 from SupportBean_S0.win:length(2)) as c5 " +
                "from SupportBean.win:length(3)";
        EPStatementSPI statement = (EPStatementSPI) epService.getEPAdministrator().createEPL(eplStmt);
        statement.addListener(listener);

        epService.getEPRuntime().sendEvent(makeBean("E1", 100, 20L));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{100, 20L, null, null, null});

        epService.getEPRuntime().sendEvent(makeBean("E1", 100, 21L));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{100, 41L, 20L, 20L, null});

        epService.getEPRuntime().sendEvent(new SupportBean_S0(1000, "S0"));
        epService.getEPRuntime().sendEvent(makeBean("E1", 100, 22L));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{100, 63L, 21L, 21L, "S0"});

        epService.getEPAdministrator().destroyAllStatements();
    }

    public void testHashSegmentedMulti() {

        String ctx = "HashSegmentedContext";
        String eplCtx = "@Name('context') create context " + ctx + " as " +
                "coalesce " +
                " consistent_hash_crc32(theString) from SupportBean, " +
                " consistent_hash_crc32(p00) from SupportBean_S0 " +
                "granularity 4 " +
                "preallocate";
        epService.getEPAdministrator().createEPL(eplCtx);
        HashCodeFuncGranularCRC32 codeFunc = new HashCodeFuncGranularCRC32(4);

        String eplStmt = "context " + ctx + " " +
                "select context.name as c0, intPrimitive as c1, id as c2 from SupportBean.win:keepall() as t1, SupportBean_S0.win:keepall() as t2 where t1.theString = t2.p00";
        EPStatementSPI statement = (EPStatementSPI) epService.getEPAdministrator().createEPL(eplStmt);
        statement.addListener(listener);

        String[] fields = "c0,c1,c2".split(",");

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 10));
        epService.getEPRuntime().sendEvent(new SupportBean_S0(1, "E2"));
        epService.getEPRuntime().sendEvent(new SupportBean("E3", 11));
        epService.getEPRuntime().sendEvent(new SupportBean_S0(2, "E4"));
        assertFalse(listener.isInvoked());

        epService.getEPRuntime().sendEvent(new SupportBean_S0(3, "E1"));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, 10, 3});
        assertIterator(statement, fields, new Object[][]{{ctx, 10, 3}});

        epService.getEPRuntime().sendEvent(new SupportBean_S0(4, "E4"));
        epService.getEPRuntime().sendEvent(new SupportBean_S0(5, "E5"));
        assertFalse(listener.isInvoked());

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 12));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, 12, 1});
        assertIterator(statement, fields, new Object[][]{{ctx, 10, 3}, {ctx, 12, 1}});
    }

    public void testHashSegmented() {

        // Comment-in to see CRC32 code.
        for (int i = 0; i < 10; i++) {
            String key = "E" + i;
            long code = HashCodeFuncGranularCRC32.computeCRC32(key) % 4;
            int hashCode = Integer.valueOf(i).hashCode() % 4;
            //System.out.println(key + " code " + code + " hashCode " + hashCode);
        }

        // test CRC32 Hash
        FilterServiceSPI filterSPI = (FilterServiceSPI) spi.getFilterService();
        String ctx = "HashSegmentedContext";
        String eplCtx = "@Name('context') create context " + ctx + " as " +
                "coalesce consistent_hash_crc32(theString) from SupportBean " +
                "granularity 4 " +
                "preallocate";
        epService.getEPAdministrator().createEPL(eplCtx);

        String eplStmt = "context " + ctx + " " +
                "select context.name as c0, theString as c1, sum(intPrimitive) as c2 from SupportBean.win:keepall() group by theString";
        EPStatementSPI statement = (EPStatementSPI) epService.getEPAdministrator().createEPL(eplStmt);
        statement.addListener(listener);
        assertEquals(4, filterSPI.getFilterCountApprox());
        AgentInstanceAssertionUtil.assertInstanceCounts(statement.getStatementContext(), 4, 0, 0, 0);

        runAssertionHash(ctx, statement, new HashCodeFuncGranularCRC32(4));
        assertEquals(0, filterSPI.getFilterCountApprox());

        // test same with SODA
        EPStatementObjectModel modelCtx = epService.getEPAdministrator().compileEPL(eplCtx);
        assertEquals(eplCtx, modelCtx.toEPL());
        EPStatement stmtCtx = epService.getEPAdministrator().create(modelCtx);
        assertEquals(eplCtx, stmtCtx.getText());
        
        statement = (EPStatementSPI) epService.getEPAdministrator().createEPL(eplStmt);
        statement.addListener(listener);
        runAssertionHash(ctx, statement, new HashCodeFuncGranularCRC32(4));

        // test with Java-hashCode String hash
        epService.getEPAdministrator().createEPL("@Name('context') create context " + ctx + " " +
                "coalesce hash_code(theString) from SupportBean " +
                "granularity 6 " +
                "preallocate");

        statement = (EPStatementSPI) epService.getEPAdministrator().createEPL("context " + ctx + " " +
                "select context.name as c0, theString as c1, sum(intPrimitive) as c2 from SupportBean.win:keepall() group by theString");
        statement.addListener(listener);
        assertEquals(6, filterSPI.getFilterCountApprox());
        AgentInstanceAssertionUtil.assertInstanceCounts(statement.getStatementContext(), 6, 0, 0, 0);

        runAssertionHash(ctx, statement, new HashCodeFuncGranularInternalHash(6));
        assertEquals(0, filterSPI.getFilterCountApprox());

        // test no pre-allocate
        epService.getEPAdministrator().createEPL("@Name('context') create context " + ctx + " " +
                "coalesce hash_code(theString) from SupportBean " +
                "granularity 16 ");

        statement = (EPStatementSPI) epService.getEPAdministrator().createEPL("context " + ctx + " " +
                "select context.name as c0, theString as c1, sum(intPrimitive) as c2 from SupportBean.win:keepall() group by theString");
        statement.addListener(listener);
        assertEquals(1, filterSPI.getFilterCountApprox());
        AgentInstanceAssertionUtil.assertInstanceCounts(statement.getStatementContext(), 0, 0, 0, 0);

        runAssertionHash(ctx, statement, new HashCodeFuncGranularInternalHash(16));
        assertEquals(0, filterSPI.getFilterCountApprox());
    }

    private void runAssertionHash(String ctx, EPStatementSPI statement, HashCodeFunc codeFunc) {

        String[] fields = "c0,c1,c2".split(",");

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 5));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, "E1", 5});
        assertIterator(statement, fields, new Object[][]{{ctx, "E1", 5}});

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 6));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, "E2", 6});
        assertIterator(statement, fields, new Object[][]{{ctx, "E1", 5}, {ctx, "E2", 6}});

        epService.getEPRuntime().sendEvent(new SupportBean("E3", 7));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, "E3", 7});
        assertIterator(statement, fields, new Object[][]{{ctx, "E1", 5}, {ctx, "E3", 7}, {ctx, "E2", 6}});

        epService.getEPRuntime().sendEvent(new SupportBean("E4", 8));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, "E4", 8});
        assertIterator(statement, fields, new Object[][]{{ctx, "E1", 5}, {ctx, "E3", 7}, {ctx, "E4", 8}, {ctx, "E2", 6}});

        epService.getEPRuntime().sendEvent(new SupportBean("E5", 9));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, "E5", 9});
        assertIterator(statement, fields, new Object[][]{{ctx, "E5", 9}, {ctx, "E1", 5}, {ctx, "E3", 7}, {ctx, "E4", 8}, {ctx, "E2", 6}});

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 10));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, "E1", 15});
        assertIterator(statement, fields, new Object[][]{{ctx, "E5", 9}, {ctx, "E1", 15}, {ctx, "E3", 7}, {ctx, "E4", 8}, {ctx, "E2", 6}});

        epService.getEPRuntime().sendEvent(new SupportBean("E4", 11));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{ctx, "E4", 19});
        assertIterator(statement, fields, new Object[][]{{ctx, "E5", 9}, {ctx, "E1", 15}, {ctx, "E3", 7}, {ctx, "E4", 19}, {ctx, "E2", 6}});

        statement.stop();
        AgentInstanceAssertionUtil.assertInstanceCounts(statement.getStatementContext(), 0, 0, 0, 0);

        assertEquals(1, spi.getContextManagementService().getContextCount());
        epService.getEPAdministrator().getStatement("context").destroy();
        assertEquals(1, spi.getContextManagementService().getContextCount());

        statement.destroy();
        assertEquals(0, spi.getContextManagementService().getContextCount());
    }

    private void assertIterator(EPStatementSPI statement, String[] fields, Object[][] expected) {
        EventBean[] rows = EPAssertionUtil.iteratorToArray(statement.iterator());
        assertIterator(rows, fields, expected);

        rows = EPAssertionUtil.iteratorToArray(statement.safeIterator());
        assertIterator(rows, fields, expected);
    }

    private void assertIterator(EventBean[] events, String[] fields, Object[][] expected) {
        Object[][] result = EPAssertionUtil.eventsToObjectArr(events, fields);
        EPAssertionUtil.assertEqualsAnyOrder(expected, result);
    }

    private SupportBean makeBean(String theString, int intPrimitive, long longPrimitive) {
        SupportBean bean = new SupportBean(theString, intPrimitive);
        bean.setLongPrimitive(longPrimitive);
        return bean;
    }

    public void testHashSegmentedBySingleRowFunc() {

        epService.getEPAdministrator().getConfiguration().addPlugInSingleRowFunction("myHash", this.getClass().getName(), "myHashFunc");
        epService.getEPAdministrator().getConfiguration().addPlugInSingleRowFunction("mySecond", this.getClass().getName(), "mySecondFunc");
        epService.getEPAdministrator().getConfiguration().addImport(this.getClass().getName());

        String eplCtx = "@Name('context') create context HashSegmentedContext as " +
                "coalesce myHash(*) from SupportBean " +
                "granularity 4 " +
                "preallocate";
        epService.getEPAdministrator().createEPL(eplCtx);

        String eplStmt = "context HashSegmentedContext select context.id as c1, myHash(*) as c2, mySecond(*, theString) as c3, "
            + this.getClass().getSimpleName() + ".mySecondFunc(*, theString) as c4 from SupportBean";
        EPStatementSPI statement = (EPStatementSPI) epService.getEPAdministrator().createEPL(eplStmt);
        statement.addListener(listener);

        String[] fields = "c1,c2,c3, c4".split(",");

        epService.getEPRuntime().sendEvent(new SupportBean("E1", 3));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{3, 3, "E1", "E1"});    // context id matches the number returned by myHashFunc

        epService.getEPRuntime().sendEvent(new SupportBean("E2", 0));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{0, 0, "E2", "E2"});

        epService.getEPRuntime().sendEvent(new SupportBean("E3", 7));
        EPAssertionUtil.assertProps(listener.assertOneGetNewAndReset(), fields, new Object[]{3, 7, "E3", "E3"});
    }

    public static int myHashFunc(SupportBean sb) {
        return sb.getIntPrimitive();
    }

    public static String mySecondFunc(SupportBean sb, String text) {
        return text;
    }

    private void makeSendScoreEvent(String typeName, EventRepresentationEnum eventRepresentationEnum, String userId, String keyword, String productId, long score) {
        Map<String, Object> theEvent = new LinkedHashMap<String, Object>();
        theEvent.put("userId", userId);
        theEvent.put("keyword", keyword);
        theEvent.put("productId", productId);
        theEvent.put("score", score);
        if (eventRepresentationEnum.isObjectArrayEvent()) {
            epService.getEPRuntime().sendEvent(theEvent.values().toArray(), typeName);
        }
        else {
            epService.getEPRuntime().sendEvent(theEvent, typeName);
        }
    }

    public interface HashCodeFunc {
        public int codeFor(String key);
    }

    public static class HashCodeFuncGranularCRC32 implements HashCodeFunc {
        private int granularity;

        public HashCodeFuncGranularCRC32(int granularity) {
            this.granularity = granularity;
        }

        public int codeFor(String key) {
            long codeMod = computeCRC32(key) % granularity;
            return (int) codeMod;
        }

        public static long computeCRC32(String key) {
            CRC32 crc = new CRC32();
            crc.update(key.getBytes());
            return crc.getValue();
        }
    }

    public static class HashCodeFuncGranularInternalHash implements HashCodeFunc {
        private int granularity;

        public HashCodeFuncGranularInternalHash(int granularity) {
            this.granularity = granularity;
        }

        public int codeFor(String key) {
            return key.hashCode() % granularity;
        }
    }

    public static class MySelectorHash implements ContextPartitionSelectorHash {

        private final Set<Integer> hashes;

        public MySelectorHash(Set<Integer> hashes) {
            this.hashes = hashes;
        }

        public Set<Integer> getHashes() {
            return hashes;
        }
    }

    private static class MySelectorFilteredHash implements ContextPartitionSelectorFiltered {

        private Set<Integer> match;

        private List<Integer> contexts = new ArrayList<Integer>();
        private LinkedHashSet<Integer> cpids = new LinkedHashSet<Integer>();

        private MySelectorFilteredHash(Set<Integer> match) {
            this.match = match;
        }

        public boolean filter(ContextPartitionIdentifier contextPartitionIdentifier) {
            ContextPartitionIdentifierHash id = (ContextPartitionIdentifierHash) contextPartitionIdentifier;
            if (match == null && cpids.contains(id.getContextPartitionId())) {
                throw new RuntimeException("Already exists context id: " + id.getContextPartitionId());
            }
            cpids.add(id.getContextPartitionId());
            contexts.add(id.getHash());
            return match.contains(id.getHash());
        }

        public List<Integer> getContexts() {
            return contexts;
        }
    }
}
