package pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.transforms.windowing.*;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class StreamingAccountPipeline {

    static final TupleTag<CommonLog> parsedMessages = new TupleTag<CommonLog>() {
    };
    static final TupleTag<String> unparsedMessages = new TupleTag<String>() {
    };

    /*
     * The logger to output status messages to.
     */
    private static final Logger LOG = LoggerFactory.getLogger(StreamingAccountPipeline.class);

    /**
     * The {@link Options} class provides the custom execution options passed by the executor at the
     * command-line.
     */
    public interface Options extends PipelineOptions {
        @Description("Input pub/sub topic")
        String getInputTopic();
        void setInputTopic(String inputTopic);

        @Description("Input topic subcription")
        String getInputSub();
        void setInputSub(String inputSub);

        @Description("BigQuery output table name")
        String getOutputTableName();
        void setOutputTableName(String outputTableName);

        @Description("output DLQ pub/sub topic")
        String getOutputDltTopic();
        void setOutputDltTopic(String inputTopic);

        @Description("output DLQ topic subcription")
        String getOutputDltSub();
        void setOutputDltSub(String inputSub);

        @Description("The Cloud Storage bucket used for writing " + "unparseable Pubsub Messages.")
        String getDeadletterBucket();
        void setDeadletterBucket(String deadletterBucket);

        @Description("I use fixed window in this case. Window duration length, in minutes")
        Integer getWindowDuration();
        void setWindowDuration(Integer windowDuration);

        @Description("Window allowed lateness, in days")
        Integer getAllowedLateness();
        void setAllowedLateness(Integer allowedLateness);
    }

    /**
     * A PTransform accepting Json and outputting tagged CommonLog with Beam Schema or raw Json string if parsing fails
     */
    public static class PubsubMessageToCommonLog extends PTransform<PCollection<String>, PCollectionTuple> {
        @Override
        public PCollectionTuple expand(PCollection<String> input) {
            return input
                    .apply("JsonToCommonLog", ParDo.of(new DoFn<String, CommonLog>() {
                                @ProcessElement
                                public void processElement(ProcessContext context) {
                                    String json = context.element();
                                    Gson gson = new Gson();
                                    try {
                                        CommonLog commonLog = gson.fromJson(json, CommonLog.class);
                                        context.output(parsedMessages, commonLog);
                                    } catch (JsonSyntaxException e) {
                                        context.output(unparsedMessages, json);
                                    }

                                }
                            })
                            .withOutputTags(parsedMessages, TupleTagList.of(unparsedMessages)));


        }
    }


    /**
     * The main entry-point for pipeline execution. This method will start the pipeline but will not
     * wait for it's execution to finish. If blocking execution is required, use the {@link
     * StreamingAccountPipeline#run(Options)} method to start the pipeline and invoke
     * {@code result.waitUntilFinish()} on the {@link PipelineResult}.
     *
     * @param args The command-line args passed by the executor.
     */
    public static void main(String[] args) {
        PipelineOptionsFactory.register(Options.class);
        Options options = PipelineOptionsFactory.fromArgs(args)
                .withValidation()
                .as(Options.class);
        run(options);
    }

    /**
     * Runs the pipeline to completion with the specified options. This method does not wait until the
     * pipeline is finished before returning. Invoke {@code result.waitUntilFinish()} on the result
     * object to block until the pipeline is finished running if blocking programmatic execution is
     * required.
     *
     * @param options The execution options.
     * @return The pipeline result.
     */
    public static PipelineResult run(Options options) {

        // Create the pipeline
        Pipeline pipeline = Pipeline.create(options);

        options.setInputTopic("projects/nttdata-c4e-bde/topics/uc1-input-topic-10");
        options.setInputSub("projects/nttdata-c4e-bde/subscriptions/uc1-input-topic-sub-10");
        options.setOutputTableName("nttdata-c4e-bde:uc1_10.account");
        options.setOutputDltTopic("projects/nttdata-c4e-bde/topics/uc1-dlq-topic-10");
        options.setOutputDltSub("projects/nttdata-c4e-bde/subscriptions/uc1-dlq-topic-sub-10");
        options.setWindowDuration(90);
        options.setAllowedLateness(10);
        /*
         * Steps:
         *  1) Read
         *  2) Transform
         *  3) Write
         */

        LOG.info("Building pipeline...");


        PCollectionTuple transformOut =
                pipeline.apply("ReadPubSubMessages", PubsubIO.readStrings()
                                .fromSubscription(options.getInputSub()))
                        .apply("ConvertMessageToCommonLog", new PubsubMessageToCommonLog());

        // Write parsed messages to BigQuery
        transformOut
                // Retrieve parsed messages
                .get(parsedMessages)
                .apply("WindowByMinute", Window.<CommonLog>into(
                                FixedWindows.of(Duration.standardSeconds(options.getWindowDuration()))).withAllowedLateness(
                                Duration.standardDays(options.getAllowedLateness()))
                        .triggering(AfterWatermark.pastEndOfWindow()
                                .withLateFirings(AfterPane.elementCountAtLeast(1)))
                        .accumulatingFiredPanes())
                .apply("WriteToBQ", BigQueryIO.<CommonLog>write().to(options.getOutputTableName())
                        .useBeamSchema()
                        .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                        .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));

        // Write unparsed messages to Cloud Storage
        transformOut
                // Retrieve unparsed messages
                .get(unparsedMessages)
                .apply("FireEvery10s", Window.<String>configure().triggering(
                                Repeatedly.forever(
                                        AfterProcessingTime.pastFirstElementInPane()
                                                .plusDelayOf(Duration.standardSeconds(10))))
                        .discardingFiredPanes())
                .apply("WriteDeadletterStorage", TextIO.write()
                        .to(options.getDeadletterBucket() + "/deadletter/*")
                        .withWindowedWrites()
                        .withNumShards(10));


        return pipeline.run();
    }
}


