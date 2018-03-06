/*
 * Copyright (C) 2017 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.sabot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Provider;

import org.antlr.runtime.ANTLRStringStream;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.BufferManager;
import org.apache.arrow.memory.RootAllocator;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.curator.utils.CloseableExecutorService;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import com.dremio.common.AutoCloseables;
import com.dremio.common.config.LogicalPlanPersistence;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.expression.FieldReference;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.parser.ExprLexer;
import com.dremio.common.expression.parser.ExprParser;
import com.dremio.common.expression.parser.ExprParser.parse_return;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.logical.data.Order;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.datastore.KVStoreProvider;
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.exec.ExecTest;
import com.dremio.exec.compile.CodeCompiler;
import com.dremio.exec.expr.ClassProducer;
import com.dremio.exec.expr.ClassProducerImpl;
import com.dremio.exec.expr.fn.FunctionImplementationRegistry;
import com.dremio.exec.expr.fn.FunctionLookupContext;
import com.dremio.exec.physical.base.AbstractPhysicalVisitor;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.Receiver;
import com.dremio.exec.physical.base.Sender;
import com.dremio.exec.physical.base.SubScan;
import com.dremio.exec.physical.config.HashJoinPOP;
import com.dremio.exec.physical.config.MergeJoinPOP;
import com.dremio.exec.physical.config.NestedLoopJoinPOP;
import com.dremio.exec.physical.config.Screen;
import com.dremio.exec.physical.config.UnionAll;
import com.dremio.exec.proto.CoordExecRPC.QueryContextInformation;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.proto.ExecProtos.FragmentHandle;
import com.dremio.exec.proto.UserBitShared.UserCredentials;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.server.NodeDebugContextProvider;
import com.dremio.exec.server.options.OptionManager;
import com.dremio.exec.server.options.OptionValue;
import com.dremio.exec.server.options.OptionValue.OptionType;
import com.dremio.exec.server.options.SystemOptionManager;
import com.dremio.exec.server.options.TypeValidators.BooleanValidator;
import com.dremio.exec.server.options.TypeValidators.DoubleValidator;
import com.dremio.exec.server.options.TypeValidators.LongValidator;
import com.dremio.exec.server.options.TypeValidators.StringValidator;
import com.dremio.exec.store.sys.PersistentStoreProvider;
import com.dremio.exec.store.sys.store.provider.KVPersistentStoreProvider;
import com.dremio.exec.testing.ExecutionControls;
import com.dremio.exec.work.AttemptId;
import com.dremio.sabot.Fixtures.Table;
import com.dremio.sabot.driver.OperatorCreatorRegistry;
import com.dremio.sabot.driver.SchemaChangeListener;
import com.dremio.sabot.exec.context.ContextInformation;
import com.dremio.sabot.exec.context.ContextInformationImpl;
import com.dremio.sabot.exec.context.OpProfileDef;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorContextImpl;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.exec.rpc.TunnelProvider;
import com.dremio.sabot.op.receiver.RawFragmentBatchProvider;
import com.dremio.sabot.op.sort.external.RecordBatchData;
import com.dremio.sabot.op.spi.BatchStreamProvider;
import com.dremio.sabot.op.spi.DualInputOperator;
import com.dremio.sabot.op.spi.Operator;
import com.dremio.sabot.op.spi.Operator.MasterState;
import com.dremio.sabot.op.spi.Operator.OperatorState;
import com.dremio.sabot.op.spi.SingleInputOperator;
import com.dremio.sabot.op.spi.SingleInputOperator.State;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.NamespaceServiceImpl;
import com.dremio.test.DremioTest;

import io.airlift.tpch.GenerationDefinition.TpchTable;
import io.airlift.tpch.TpchGenerator;


public class BaseTestOperator extends ExecTest {

  public static int DEFAULT_BATCH = 4095;

  @ClassRule
  public static TemporaryFolder testFolder = new TemporaryFolder();

  protected static OperatorTestContext testContext;
  private final List<AutoCloseable> testCloseables = new ArrayList<>();
  private BufferAllocator testAllocator;

  @BeforeClass
  public static void setup() {
    testContext = new OperatorTestContext();
    testContext.setup();
  }

  @AfterClass
  public static void cleanupAfterClass() throws Exception{
    testContext.close();
  }

  @Before
  public void setupBeforeTest() {
    testAllocator = testContext.allocator.newChildAllocator(TEST_NAME.getMethodName(), 0, Long.MAX_VALUE);
    testCloseables.add(testAllocator);
  }

  public BufferAllocator getTestAllocator(){
    return testAllocator;
  }

  @After
  public void cleanupAfterTest() throws Exception {
    Collections.reverse(testCloseables);
    AutoCloseables.close(testCloseables);
    testContext.resetConfig();
  }

  public AutoCloseable with(final StringValidator validator, final String value){
    final String oldValue = testContext.getOptions().getOption(validator);
    testContext.getOptions().setOption(OptionValue.createString(OptionType.SYSTEM, validator.getOptionName(), value));
    return new AutoCloseable(){
      @Override
      public void close() throws Exception {
        testContext.getOptions().setOption(OptionValue.createString(OptionType.SYSTEM, validator.getOptionName(), oldValue));
      }};
  }

  public AutoCloseable with(final LongValidator validator, final long value){
    final long oldValue = testContext.getOptions().getOption(validator);
    testContext.getOptions().setOption(OptionValue.createLong(OptionType.SYSTEM, validator.getOptionName(), value));
    return new AutoCloseable(){
      @Override
      public void close() throws Exception {
        testContext.getOptions().setOption(OptionValue.createLong(OptionType.SYSTEM, validator.getOptionName(), oldValue));
      }};
  }

  public AutoCloseable with(final DoubleValidator validator, final double value){
    final double oldValue = testContext.getOptions().getOption(validator);
    testContext.getOptions().setOption(OptionValue.createDouble(OptionType.SYSTEM, validator.getOptionName(), value));
    return new AutoCloseable(){
      @Override
      public void close() throws Exception {
        testContext.getOptions().setOption(OptionValue.createDouble(OptionType.SYSTEM, validator.getOptionName(), oldValue));
      }};
  }

  public AutoCloseable with(final BooleanValidator validator, final boolean value){
    final boolean oldValue = testContext.getOptions().getOption(validator);
    testContext.getOptions().setOption(OptionValue.createBoolean(OptionType.SYSTEM, validator.getOptionName(), value));
    return new AutoCloseable(){
      @Override
      public void close() throws Exception {
        testContext.getOptions().setOption(OptionValue.createBoolean(OptionType.SYSTEM, validator.getOptionName(), oldValue));
      }};
  }

  /**
   * Create a new operator described by the provided operator class. Test harness will automatically create a test allocator. Test will automatically close operator created using this method.
   * @param clazz The operator class mapped to the PhysicalOperator provided.
   * @param pop SqlOperatorImpl configuration
   * @param batchProviders List of batch providers. Only needed in case of creating a Receiver
   * @return The SqlOperatorImpl.
   * @throws Exception
   */
  protected <T extends Operator> T newOperator(Class<T> clazz, PhysicalOperator pop, int targetBatchSize, final RawFragmentBatchProvider[]... batchProviders) throws Exception {
    return newOperator(clazz, pop, targetBatchSize, null, batchProviders);
  }

  protected <T extends Operator> T newOperator(Class<T> clazz, PhysicalOperator pop, int targetBatchSize, TunnelProvider tunnelProvider, final RawFragmentBatchProvider[]... batchProviders) throws Exception {

    final BatchStreamProvider provider = new BatchStreamProvider(){

      @Override
      public RawFragmentBatchProvider[] getBuffers(int senderMajorFragmentId) {
        if(!(batchProviders.length > senderMajorFragmentId)) {
          throw new IllegalStateException(String.format("Attempted to get a batch provider but the test didn't provide enough. The test provided %d but you asked for index %d.", batchProviders.length, senderMajorFragmentId));
        }
        return batchProviders[senderMajorFragmentId];
      }

      @Override
      public boolean isPotentiallyBlocked() {
        return false;
      }
    };

    final BufferAllocator childAllocator = testAllocator.newChildAllocator(
        pop.getClass().getSimpleName(),
        pop.getInitialAllocation(),
        pop.getMaxAllocation() == 0 ? Long.MAX_VALUE : pop.getMaxAllocation());

    // we don't close child allocator as the operator context will manage this.
    final OperatorContextImpl context = testContext.getNewOperatorContext(childAllocator, pop, targetBatchSize);
    testCloseables.add(context);

    // mock FEC
    FragmentExecutionContext fec = Mockito.mock(FragmentExecutionContext.class);
    Mockito.when(fec.getSchemaUpdater()).thenReturn(Mockito.mock(SchemaChangeListener.class));

    CreatorVisitor visitor = new CreatorVisitor(fec, provider, tunnelProvider);
    Operator o = pop.accept(visitor, context);
    testCloseables.add(o);

    if(clazz.isAssignableFrom(o.getClass())){
      // make sure that the set of closeables that should be cleaned in finally are empty since we were succesful.
      return (T) o;
    }else{
      throw new RuntimeException(String.format("Unable to convert from expected operator of type %s to type %s.", o.getClass().getName(), clazz.getName()));
    }
  }

  protected static class OperatorTestContext implements AutoCloseable{

    SabotConfig config = DEFAULT_SABOT_CONFIG;
    final ScanResult result = CLASSPATH_SCAN_RESULT;
    final ExecutorService inner = Executors.newCachedThreadPool();
    final CloseableExecutorService executor = new CloseableExecutorService(inner);
    final BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
    final LogicalPlanPersistence persistence = new LogicalPlanPersistence(config, result);

    OperatorCreatorRegistry registry = new OperatorCreatorRegistry(result);

    KVStoreProvider storeProvider;
    PersistentStoreProvider provider;
    SystemOptionManager options;
    CodeCompiler compiler;
    ExecutionControls ec;
    FunctionLookupContext functionLookup;
    ContextInformation contextInformation;

    public void setup() {
      try {
        storeProvider = new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
        storeProvider.start();

        final Provider<KVStoreProvider> storeProviderProvider = new Provider<KVStoreProvider>() {
          @Override
          public KVStoreProvider get() {
            return storeProvider;
          }
        };
        provider = new KVPersistentStoreProvider(storeProviderProvider);
        options = new SystemOptionManager(result, persistence, provider);
        options.init();
        compiler = new CodeCompiler(config, options);
        ec = new ExecutionControls(options, NodeEndpoint.getDefaultInstance());
        functionLookup = new FunctionImplementationRegistry(config, result);

        contextInformation = new ContextInformationImpl(UserCredentials.getDefaultInstance(), QueryContextInformation.getDefaultInstance());

      }catch(Exception e){
        throw new RuntimeException(e);
      }
    }

    public OptionManager getOptions(){
      return options;
    }

    public void setRegistry(OperatorCreatorRegistry registry) {
      this.registry = registry;
    }

    protected FunctionLookupContext getFunctionLookupContext(){
      return functionLookup;
    }

    protected OperatorContextImpl getNewOperatorContext(BufferAllocator child, PhysicalOperator pop, int targetBatchSize) throws Exception {
      OperatorStats stats = new OperatorStats(new OpProfileDef(1, 1, 1), child);
      final NamespaceService namespaceService = new NamespaceServiceImpl(testContext.storeProvider);
      final FragmentHandle handle = FragmentHandle.newBuilder()
        .setQueryId(new AttemptId().toQueryId())
        .setMinorFragmentId(0)
        .setMajorFragmentId(0)
        .build();
      return new OperatorContextImpl(
          config,
          handle,
          pop,
          child,
          compiler,
          stats,
          ec,
          inner,
          functionLookup,
          contextInformation,
          options,
          namespaceService,
          NodeDebugContextProvider.NOOP,
          targetBatchSize);
    }

    public ClassProducer newClassProducer(BufferManager bufferManager) {
      return new ClassProducerImpl(compiler, functionLookup, contextInformation, bufferManager);
    }


    public OperatorCreatorRegistry getOperatorCreatorRegistry(){
      return registry;
    }
    @Override
    public void close() throws Exception {
      AutoCloseables.close(options, provider, storeProvider, allocator, executor);
    }

    /**
     * update configuration passed to the operators, this won't affect what has already been initialized as
     * part of this OperatorTestContext
     */
    public void updateConfig(SabotConfig config) {
      this.config = config;
    }

    private void resetConfig() {
      config = DEFAULT_SABOT_CONFIG;
    }
  }

  public static NamedExpression n(String name){
    return new NamedExpression(f(name), f(name));
  }

  public static FieldReference f(String name){
    return new FieldReference(name);
  }

  public static NamedExpression n(String expr, String name){
    return new NamedExpression(parseExpr(expr), new FieldReference(name));
  }

  protected static LogicalExpression parseExpr(String expr) {
    ExprLexer lexer = new ExprLexer(new ANTLRStringStream(expr));
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    ExprParser parser = new ExprParser(tokens);
    try {
      return parser.parse().e;
    } catch (RecognitionException e) {
      throw new RuntimeException("Error parsing expression: " + expr);
    }
  }

  protected Order.Ordering ordering(String expression, RelFieldCollation.Direction direction, RelFieldCollation.NullDirection nullDirection) {
    return new Order.Ordering(direction, parseExpr(expression), nullDirection);
  }

  protected NamedExpression straightName(String name){
    return new NamedExpression(new FieldReference(name), new FieldReference(name));
  }

  private static class CreatorVisitor extends AbstractPhysicalVisitor<Operator, OperatorContext,  ExecutionSetupException> {

    private final BatchStreamProvider batchStreamProvider;
    private final TunnelProvider tunnelProvider;
    private final FragmentExecutionContext fec;

    public CreatorVisitor(FragmentExecutionContext fec, BatchStreamProvider batchStreamProvider, TunnelProvider tunnelProvider) {
      super();
      this.batchStreamProvider = batchStreamProvider;
      this.tunnelProvider = tunnelProvider;
      this.fec = fec;
    }

    @Override
    public Operator visitUnion(UnionAll config, OperatorContext context) throws ExecutionSetupException {
      return testContext.getOperatorCreatorRegistry().getDualInputOperator(context, config);
    }

    @Override
    public Operator visitMergeJoin(MergeJoinPOP config, OperatorContext context) throws ExecutionSetupException {
      return testContext.getOperatorCreatorRegistry().getDualInputOperator(context, config);
    }

    @Override
    public Operator visitNestedLoopJoin(NestedLoopJoinPOP join, OperatorContext value) throws ExecutionSetupException {
      return testContext.getOperatorCreatorRegistry().getDualInputOperator(value, join);
    }

    @Override
    public Operator visitHashJoin(HashJoinPOP config, OperatorContext context) throws ExecutionSetupException {
      return testContext.getOperatorCreatorRegistry().getDualInputOperator(context, config);
    }

    @Override
    public Operator visitSender(Sender config, OperatorContext context) throws ExecutionSetupException {
      return testContext.getOperatorCreatorRegistry().getTerminalOperator(tunnelProvider, context, config);
    }

    @Override
    public Operator visitReceiver(Receiver config, OperatorContext context) throws ExecutionSetupException {
      return testContext.getOperatorCreatorRegistry().getReceiverOperator(batchStreamProvider, context, config);
    }

    @Override
    public Operator visitSubScan(SubScan config, OperatorContext context) throws ExecutionSetupException {
      return testContext.getOperatorCreatorRegistry().getProducerOperator(fec, context, config);
    }

    @Override
    public Operator visitScreen(Screen config, OperatorContext context) throws ExecutionSetupException {
      return testContext.getOperatorCreatorRegistry().getTerminalOperator(tunnelProvider, context, config);
    }

    @Override
    public Operator visitOp(PhysicalOperator config, OperatorContext context) throws ExecutionSetupException {
      return testContext.getOperatorCreatorRegistry().getSingleInputOperator(context, config);
    }

  }

  protected <T extends SingleInputOperator> void basicTests(PhysicalOperator pop, Class<T> clazz, TpchTable table, double scale, Long expectedCount, int batchSize) throws Exception {

    leakTests(pop, clazz, table, scale, batchSize);

    // full lifecycle:
    assertSingleInput(pop, clazz, table, scale, expectedCount, batchSize);

  }

  protected <T extends SingleInputOperator> void leakTests(PhysicalOperator pop, Class<T> clazz, TpchTable table, double scale, int batchSize) throws Exception {
    // straight close, no leak.
    try(T op = newOperator(clazz, pop, batchSize);
        TpchGenerator generator = TpchGenerator.singleGenerator(table, scale, getTestAllocator());
        ){
    }

    // setup, no execute, no leak.
    try(T op = newOperator(clazz, pop, batchSize);
        TpchGenerator generator = TpchGenerator.singleGenerator(table, scale, getTestAllocator());
        ){
      op.setup(generator.getOutput());
    }

    // setup, single consume, no leak.
    try(T op = newOperator(clazz, pop, batchSize);
        TpchGenerator generator = TpchGenerator.singleGenerator(table, scale, getTestAllocator());
        ){
      op.setup(generator.getOutput());
      op.consumeData(generator.next(batchSize));
    }
  }

  protected <T extends SingleInputOperator> void validateSingle(PhysicalOperator pop, Class<T> clazz, TpchTable table, double scale, Fixtures.Table result) throws Exception {
    assertSingleInput(pop, clazz, table, scale, null, 4095, result);
  }

  protected <T extends SingleInputOperator> void assertSingleInput(PhysicalOperator pop, Class<T> clazz, TpchTable table, double scale, Long expectedCount, int batchSize) throws Exception {
    assertSingleInput(pop, clazz, table, scale, expectedCount, batchSize, null);
  }

  protected <T extends SingleInputOperator> void assertSingleInput(PhysicalOperator pop, Class<T> clazz, TpchTable table, double scale, Long expectedCount, int batchSize, Table result) throws Exception {
    TpchGenerator generator = TpchGenerator.singleGenerator(table, scale, getTestAllocator());
    validateSingle(pop, clazz, generator, result, batchSize, expectedCount);
  }

  protected <T extends SingleInputOperator> void validateSingle(PhysicalOperator pop, Class<T> clazz, Fixtures.Table input, Fixtures.Table result) throws Exception {
    validateSingle(pop, clazz, input.toGenerator(getTestAllocator()), result, DEFAULT_BATCH);
  }

  protected <T extends SingleInputOperator> void validateSingle(PhysicalOperator pop, Class<T> clazz, Fixtures.Table input, Fixtures.Table result, int batchSize) throws Exception {
    validateSingle(pop, clazz, input.toGenerator(getTestAllocator()), result, batchSize);
  }

  protected <T extends SingleInputOperator> void validateSingle(PhysicalOperator pop, Class<T> clazz, Generator generator, Fixtures.Table result, int batchSize) throws Exception {
    validateSingle(pop, clazz, generator, result, batchSize, null);
  }

  private <T extends SingleInputOperator> void validateSingle(PhysicalOperator pop, Class<T> clazz, Generator generator, Fixtures.Table result, int batchSize, Long expected) throws Exception {
    long recordCount = 0;
    final List<RecordBatchData> data = new ArrayList<>();
    try(
        T op = newOperator(clazz, pop, batchSize);
        Generator closeable = generator;
        ){

      final VectorAccessible output = op.setup(generator.getOutput());
      int count;
      while((count = generator.next(batchSize)) != 0){
        assertState(op, State.CAN_CONSUME);
        op.consumeData(count);
        while(op.getState() == State.CAN_PRODUCE){
          int recordsOutput = op.outputData();
          recordCount += recordsOutput;
          if(result != null && recordsOutput > 0){
            data.add(new RecordBatchData(output, getTestAllocator()));
          }
        }
      }

      if(op.getState() == State.CAN_CONSUME){
        op.noMoreToConsume();
      }

      while(op.getState() == State.CAN_PRODUCE){
        int recordsOutput = op.outputData();
        recordCount += recordsOutput;
        if(recordsOutput > 0){
          data.add(new RecordBatchData(output, getTestAllocator()));
        }
      }

      assertState(op, State.DONE);
      if(result != null){
        result.checkValid(data);
      }

      if(expected != null){
        Assert.assertEquals((long) expected, recordCount);
      }
    } finally {
      AutoCloseables.close(data);
    }
  }

  /**
   * Check whether a dual input operator with the provided generators produces the expected output
   * @param pop The configuration for the operator
   * @param clazz The clazz that implements the operator.
   * @param left The generator to provide the left input.
   * @param right The generator to provide the right input.
   * @param batchSize The target record batch size.
   * @param result The expected result.
   * @param isProduceRequired whether CAN_PRODUCE state should be called or not
   * @throws Exception
   */
  protected <T extends DualInputOperator> void validateDual(
      PhysicalOperator pop,
      Class<T> clazz,
      Generator left,
      Generator right,
      int batchSize,
      Table result,
      boolean isProduceRequired) throws Exception {

    final List<RecordBatchData> data = new ArrayList<>();
    try(
        Generator leftGen = left;
        Generator rightGen = right;
        ){

      // op is added to closeable list and will be closed when test finished. no need to close here.
      T op = newOperator(clazz, pop, batchSize);

      final VectorAccessible output = op.setup(leftGen.getOutput(), right.getOutput());

      outside: while(true){
        switch(op.getState()){
        case CAN_CONSUME_L:
          int leftCount = leftGen.next(batchSize);
          if(leftCount > 0){
            op.consumeDataLeft(leftCount);
          }else{
            op.noMoreToConsumeLeft();
          }
          break;
        case CAN_CONSUME_R:
          int rightCount = rightGen.next(batchSize);
          if(rightCount > 0){
            op.consumeDataRight(rightCount);
          }else{
            op.noMoreToConsumeRight();
          }
          break;
        case CAN_PRODUCE:
          int outputCount = op.outputData();
          if(outputCount > 0
            || (outputCount == 0 && result.isExpectZero())) {
            data.add(new RecordBatchData(output, getTestAllocator()));
          }
          break;
        case DONE:
          break outside;
        default:
          throw new UnsupportedOperationException();
        }
      }

      assertState(op, State.DONE);
      if (!isProduceRequired && data.isEmpty() && result.isExpectZero()) {
        ((VectorContainer)output).setAllCount(0);
        data.add(new RecordBatchData(output, getTestAllocator()));
      }
      result.checkValid(data);

    } finally {
      AutoCloseables.close(data);
    }
  }

  /**
   * Check whether a dual input operator with the provided generators produces the expected output
   * assumes that isProduceRequired is true
   * @param pop The configuration for the operator
   * @param clazz The clazz that implements the operator.
   * @param left The generator to provide the left input.
   * @param right The generator to provide the right input.
   * @param batchSize The target record batch size.
   * @param result The expected result.
   * @throws Exception
   */
  protected <T extends DualInputOperator> void validateDual(
    PhysicalOperator pop,
    Class<T> clazz,
    Generator left,
    Generator right,
    int batchSize,
    Table result) throws Exception {

    validateDual(pop, clazz, left, right, batchSize, result, true);
  }

    public static void assertState(Operator operator, MasterState state){
    Assert.assertEquals(state,  operator.getState().getMasterState());
  }

  public static void assertState(Operator operator, OperatorState state){
    Assert.assertEquals(state.getMasterState(),  operator.getState().getMasterState());
  }

  public static LogicalExpression toExpr(String expr) throws RecognitionException{
    ExprLexer lexer = new ExprLexer(new ANTLRStringStream(expr));
    CommonTokenStream tokens = new CommonTokenStream(lexer);
    ExprParser parser = new ExprParser(tokens);
    parse_return ret = parser.parse();
    return ret.e;
  }

}
