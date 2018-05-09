package org.deeplearning4j.examples.recurrent.character;

import org.apache.commons.io.FilenameUtils;
import org.datavec.api.util.ClassPathResource;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.BackpropType;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.GravesLSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions.LossFunction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Random;

// Original https://github.com/deeplearning4j/dl4j-examples/blob/master/dl4j-examples/src/main/java/org/deeplearning4j/examples/recurrent/character/GravesLSTMCharModellingExample.java

/**
 * GravesLSTM Character modelling example
 *
 * @author Alex Black
 * <p>
 * Example: Train a LSTM RNN to generates text, one character at a time.
 * This example is somewhat inspired by Andrej Karpathy's blog post,
 * "The Unreasonable Effectiveness of Recurrent Neural Networks"
 * http://karpathy.github.io/2015/05/21/rnn-effectiveness/
 * <p>
 * This example is set up to train on the Complete Works of William Shakespeare, downloaded
 * from Project Gutenberg. Training on other text sources should be relatively easy to implement.
 * <p>
 * For more details on RNNs in DL4J, see the following:
 * http://deeplearning4j.org/usingrnns
 * http://deeplearning4j.org/lstm
 * http://deeplearning4j.org/recurrentnetwork
 */
public class GravesLSTMCharModellingExample {
    public static final String DATA_VAR = "DEVOXXUK_GHDDL_DATA";
    public static final String DATA_DIR = System.getenv(DATA_VAR);

    private static final String GENERATOR_MODEL = "GravesLSTMCharModelling_model.zip";

    // TODO set this to the location of a plain ASCII text file that you wish to train the network on
    private static final String TRAINING_TEXT_FILE = "PLEASE_PUT_THE_LOCATION_OF_YOUR_TEXT_FILE_HERE";

    public static void main(String[] args) throws Exception {
        if (null == DATA_DIR) {
            System.err.println("Please set the environment variable: " + DATA_VAR + " to the directory where you wish to store data files");
            System.exit(1);
        }

        if ("PLEASE_PUT_THE_LOCATION_OF_YOUR_TEXT_FILE_HERE".equals(TRAINING_TEXT_FILE)) {
            System.err.println("Please update your code to set TRAINING_TEXT_FILE to the location of an ASCII text file to train with");
            System.exit(1);
        }

        GravesLSTMCharModellingExample textGenerator = new GravesLSTMCharModellingExample();

        try {
            if (args.length > 0) {
                switch (args[0]) {
                    case "train":
                        textGenerator.createAndTrainModel(true);
                        break;
                    case "generate":
                        textGenerator.generate();
                        break;
                    default:
                        usage();
                        break;
                }
            } else {
                usage();
                System.exit(1);
            }
            System.exit(0);
        } catch (Exception e) {
            System.err.println("Caught error: " + e.toString());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("Usage: train|generate");
    }

    public void createAndTrainModel(boolean enableUi) throws Exception {
        int lstmLayerSize = 200;                    //Number of units in each GravesLSTM layer
        int miniBatchSize = 32;                        //Size of mini batch to use when  training
        int exampleLength = 1000;                    //Length of each training example sequence to use. This could certainly be increased
        int tbpttLength = 50;                       //Length for truncated backpropagation through time. i.e., do parameter updates ever 50 characters
        int numEpochs = 1;                            //Total number of training epochs
        int generateSamplesEveryNMinibatches = 10;  //How frequently to generate samples from the network? 1000 characters / 50 tbptt length: 20 parameter updates per minibatch
        int nSamplesToGenerate = 4;                    //Number of samples to generate after each training epoch
        int nCharactersToSample = 300;                //Length of each sample to generate
        String generationInitialization = null;        //Optional character initialization; a random character is used if null
        // Above is Used to 'prime' the LSTM with a character sequence to continue/complete.
        // Initialization characters must all be in CharacterIterator.getMinimalCharacterSet() by default
        Random rng = new Random(12345);

        //Get a DataSetIterator that handles vectorization of text into something we can use to train
        // our GravesLSTM network.
        CharacterIterator iter = getTextIterator(miniBatchSize, exampleLength);
        int nOut = iter.totalOutcomes();

        //Set up network configuration:
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT).iterations(1)
                .learningRate(0.1)
                .seed(12345)
                .regularization(true)
                .l2(0.001)
                .weightInit(WeightInit.XAVIER)
                .updater(Updater.RMSPROP)
                .list()
                .layer(0, new GravesLSTM.Builder().nIn(iter.inputColumns()).nOut(lstmLayerSize)
                        .activation(Activation.TANH).build())
                .layer(1, new GravesLSTM.Builder().nIn(lstmLayerSize).nOut(lstmLayerSize)
                        .activation(Activation.TANH).build())
                .layer(2, new RnnOutputLayer.Builder(LossFunction.MCXENT).activation(Activation.SOFTMAX)        //MCXENT + softmax for classification
                        .nIn(lstmLayerSize).nOut(nOut).build())
                .backpropType(BackpropType.TruncatedBPTT).tBPTTForwardLength(tbpttLength).tBPTTBackwardLength(tbpttLength)
                .pretrain(false).backprop(true)
                .build();

        MultiLayerNetwork net = new MultiLayerNetwork(conf);
        net.init();
        net.setListeners(new ScoreIterationListener(50));

        //Print the  number of parameters in the network (and for each layer)
        Layer[] layers = net.getLayers();
        int totalNumParams = 0;
        for (int i = 0; i < layers.length; i++) {
            int nParams = layers[i].numParams();
            System.out.println("Number of parameters in layer " + i + ": " + nParams);
            totalNumParams += nParams;
        }
        System.out.println("Total number of network parameters: " + totalNumParams);

        //Do training, and then generate and print samples from network
        int miniBatchNumber = 0;
        for (int i = 0; i < numEpochs; i++) {
            System.out.println("Epoch=====================" + i);
            while (iter.hasNext()) {
                DataSet ds = iter.next();
                net.fit(ds);
                if (++miniBatchNumber % generateSamplesEveryNMinibatches == 0) {
                    System.out.println("--------------------");
                    System.out.println("Completed " + miniBatchNumber + " minibatches of size " + miniBatchSize + "x" + exampleLength + " characters");
                    generateSamples(generationInitialization, net, iter, rng, nCharactersToSample, nSamplesToGenerate);
                }
            }

            iter.reset();    //Reset iterator for another epoch
        }

        System.out.println("\n\nExample complete");
        saveModel(net, GENERATOR_MODEL);
    }

    public void generate() throws Exception {
        int miniBatchSize = 32;                        //Size of mini batch to use when  training
        int exampleLength = 1000;                    //Length of each training example sequence to use. This could certainly be increased
        int tbpttLength = 50;                       //Length for truncated backpropagation through time. i.e., do parameter updates ever 50 characters
        int numEpochs = 1;                            //Total number of training epochs
        int generateSamplesEveryNMinibatches = 10;  //How frequently to generate samples from the network? 1000 characters / 50 tbptt length: 20 parameter updates per minibatch
        int nSamplesToGenerate = 4;                    //Number of samples to generate after each training epoch
        int nCharactersToSample = 300;                //Length of each sample to generate
        String generationInitialization = null;
        Random rng = new Random(12345);

        CharacterIterator iter = getTextIterator(miniBatchSize, exampleLength);

        MultiLayerNetwork net = loadModel(GENERATOR_MODEL);
        generateSamples(generationInitialization, net, iter, rng, nCharactersToSample, nSamplesToGenerate);
    }

    private void generateSamples(String generationInitialization, MultiLayerNetwork net, CharacterIterator iter, Random rng, int nCharactersToSample, int nSamplesToGenerate) throws Exception {
        System.out.println("Sampling characters from network given initialization \"" + (generationInitialization == null ? "" : generationInitialization) + "\"");
        String[] samples = sampleCharactersFromNetwork(generationInitialization, net, iter, rng, nCharactersToSample, nSamplesToGenerate);
        for (int j = 0; j < samples.length; j++) {
            System.out.println("----- Sample " + j + " -----");
            System.out.println(samples[j]);
            System.out.println();
        }
    }

    /**
     * Set up and return a simple DataSetIterator that does vectorization based on the text.
     *
     * @param miniBatchSize  Number of text segments in each training mini-batch
     * @param sequenceLength Number of characters in each text segment.
     */
    public static CharacterIterator getTextIterator(int miniBatchSize, int sequenceLength) throws Exception {
        File f = new File(TRAINING_TEXT_FILE);
        if (!f.exists()) throw new IOException("File does not exist: " + TRAINING_TEXT_FILE);    //Download problem?

        char[] validCharacters = CharacterIterator.getMinimalCharacterSet();    //Which characters are allowed? Others will be removed
        return new CharacterIterator(TRAINING_TEXT_FILE, Charset.forName("UTF-8"),
                miniBatchSize, sequenceLength, validCharacters, new Random(12345));
    }

    /**
     * Generate a sample from the network, given an (optional, possibly null) initialization. Initialization
     * can be used to 'prime' the RNN with a sequence you want to extend/continue.<br>
     * Note that the initalization is used for all samples
     *
     * @param initialization     String, may be null. If null, select a random character as initialization for all samples
     * @param charactersToSample Number of characters to sample from network (excluding initialization)
     * @param net                MultiLayerNetwork with one or more GravesLSTM/RNN layers and a softmax output layer
     * @param iter               CharacterIterator. Used for going from indexes back to characters
     */
    private static String[] sampleCharactersFromNetwork(String initialization, MultiLayerNetwork net,
                                                        CharacterIterator iter, Random rng, int charactersToSample, int numSamples) {
        //Set up initialization. If no initialization: use a random character
        if (initialization == null) {
            initialization = String.valueOf(iter.getRandomCharacter());
        }

        //Create input for initialization
        INDArray initializationInput = Nd4j.zeros(numSamples, iter.inputColumns(), initialization.length());
        char[] init = initialization.toCharArray();
        for (int i = 0; i < init.length; i++) {
            int idx = iter.convertCharacterToIndex(init[i]);
            for (int j = 0; j < numSamples; j++) {
                initializationInput.putScalar(new int[]{j, idx, i}, 1.0f);
            }
        }

        StringBuilder[] sb = new StringBuilder[numSamples];
        for (int i = 0; i < numSamples; i++) sb[i] = new StringBuilder(initialization);

        //Sample from network (and feed samples back into input) one character at a time (for all samples)
        //Sampling is done in parallel here
        net.rnnClearPreviousState();
        INDArray output = net.rnnTimeStep(initializationInput);
        output = output.tensorAlongDimension(output.size(2) - 1, 1, 0);    //Gets the last time step output

        for (int i = 0; i < charactersToSample; i++) {
            //Set up next input (single time step) by sampling from previous output
            INDArray nextInput = Nd4j.zeros(numSamples, iter.inputColumns());
            //Output is a probability distribution. Sample from this for each example we want to generate, and add it to the new input
            for (int s = 0; s < numSamples; s++) {
                double[] outputProbDistribution = new double[iter.totalOutcomes()];
                for (int j = 0; j < outputProbDistribution.length; j++)
                    outputProbDistribution[j] = output.getDouble(s, j);
                int sampledCharacterIdx = sampleFromDistribution(outputProbDistribution, rng);

                nextInput.putScalar(new int[]{s, sampledCharacterIdx}, 1.0f);        //Prepare next time step input
                sb[s].append(iter.convertIndexToCharacter(sampledCharacterIdx));    //Add sampled character to StringBuilder (human readable output)
            }

            output = net.rnnTimeStep(nextInput);    //Do one time step of forward pass
        }

        String[] out = new String[numSamples];
        for (int i = 0; i < numSamples; i++) out[i] = sb[i].toString();
        return out;
    }

    /**
     * Given a probability distribution over discrete classes, sample from the distribution
     * and return the generated class index.
     *
     * @param distribution Probability distribution over classes. Must sum to 1.0
     */
    public static int sampleFromDistribution(double[] distribution, Random rng) {
        double d = 0.0;
        double sum = 0.0;
        for (int t = 0; t < 10; t++) {
            d = rng.nextDouble();
            sum = 0.0;
            for (int i = 0; i < distribution.length; i++) {
                sum += distribution[i];
                if (d <= sum) return i;
            }
            //If we haven't found the right index yet, maybe the sum is slightly
            //lower than 1 due to rounding error, so try again.
        }
        //Should be extremely unlikely to happen if distribution is a valid probability distribution
        throw new IllegalArgumentException("Distribution is invalid? d=" + d + ", sum=" + sum);
    }

    public static MultiLayerNetwork saveModel(MultiLayerNetwork model, String fileName) throws Exception {
        File locationModelFile = new File(fileName);
        if (null != DATA_DIR) {
            locationModelFile = new File(DATA_DIR, fileName);
        }
        boolean saveUpdater = false;
        System.out.println("Saving model to " + locationModelFile);
        ModelSerializer.writeModel(model, locationModelFile, saveUpdater);
        System.out.println("Model saved");
        return model;
    }

    public MultiLayerNetwork loadModel(String fileName) throws Exception {
        File locationModelFile = new File(fileName);
        if (null != DATA_DIR) {
            locationModelFile = new File(DATA_DIR, fileName);
        }
        System.out.println("Loading model from " + locationModelFile);
        return ModelSerializer.restoreMultiLayerNetwork(locationModelFile);
    }
}