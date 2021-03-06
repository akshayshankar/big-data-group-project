package genetic_algorithm;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.SequenceFile.Reader.Option;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.mapred.lib.IdentityReducer;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

@SuppressWarnings("deprecation")
public class MapReduce extends Configured implements Tool {

	public static class InitializerMapper extends MapReduceBase
			implements Mapper<IntArrayWritable, FloatWritable, IntArrayWritable, FloatWritable> {
		Random rng;
		int num_of_features;
		IntWritable[] individual;

		@Override
		public void configure(JobConf jc) {
			num_of_features = Integer.parseInt(jc.get("ga.number_of_features"));
			rng = new Random(System.nanoTime());
			individual = new IntWritable[num_of_features];
		}

		public void map(IntArrayWritable key, FloatWritable value, OutputCollector<IntArrayWritable, FloatWritable> oc,
				Reporter rep) throws IOException {

			// Generate initial individuals
			for (int i = 0; i < 10; i++) {
				for (int l = 0; l < num_of_features; l++) {
					int ind = rng.nextBoolean() ? 0 : 1;
					individual[l] = new IntWritable(ind);
				}

				// Send the individual
				oc.collect(new IntArrayWritable(individual), new FloatWritable(0));
			}
		}
	}

	public static class GAMapper extends MapReduceBase
			implements Mapper<IntArrayWritable, FloatWritable, IntArrayWritable, FloatWritable> {
		float max_fitness = -1;
		IntArrayWritable max_individual;
		private String mapTaskId = "";
		float fit = 0;
		JobConf conf;
		int pop = 1;

		@Override
		public void configure(JobConf job) {
			conf = job;
			mapTaskId = job.get("mapred.task.id");
			// pop = Integer.parseInt(job.get("ga.populationPerMapper"));
		}

		int processedInd = 0;

		public void map(IntArrayWritable key, FloatWritable value, OutputCollector<IntArrayWritable, FloatWritable> oc,
				Reporter rep) throws IOException {

			if (value.get() != 0) {
				IntWritable[] individual = key.getArray();
				fit = value.get();
				// Keep track of the maximum fitness
				if (fit > max_fitness) {
					max_fitness = fit;
					max_individual = new IntArrayWritable(individual);
				}

				// Write the Individual and fitness value
				oc.collect(key, new FloatWritable(fit));

				processedInd++;
				if (processedInd >= 10) {
					closeAndWrite();
				}
			}
		}

		public void closeAndWrite() throws IOException {
			// At the end of Map(), write the best found individual to a file
			Path tmpDir = new Path("/akshank/GA");
			Path outDir = new Path(tmpDir, "global-map");

			// HDFS does not allow multiple mappers to write to the same file,
			// hence create
			// one for each mapper
			Path outFile = new Path(outDir, mapTaskId);
			FileSystem fileSys = FileSystem.get(conf);
			SequenceFile.Writer writer = SequenceFile.createWriter(fileSys, conf, outFile, IntArrayWritable.class,
					FloatWritable.class, CompressionType.NONE);

			writer.append(max_individual, new FloatWritable(max_fitness));
			writer.close();
		}
	}

	public static class GAReducer extends MapReduceBase
			implements Reducer<IntArrayWritable, FloatWritable, IntArrayWritable, FloatWritable> {

		int tournamentSize = 5;
		IntWritable[][] tournamentInd;
		float[] tournamentFitness = new float[2 * tournamentSize];

		int processedIndividuals = 0;
		int r = 0;
		IntArrayWritable[] ind = new IntArrayWritable[2];
		Random rng;
		int pop = 1;
		int num_of_features;

		// Generate random rumber
		GAReducer() {
			rng = new Random(System.nanoTime());
		}

		// Obtain job configuration details
		@Override
		public void configure(JobConf jc) {
			num_of_features = Integer.parseInt(jc.get("ga.number_of_features"));
			tournamentInd = new IntWritable[2 * tournamentSize][num_of_features];
			// pop = Integer.parseInt(jc.get("ga.populationPerMapper"));
		}

		void crossover() {
			// Perform uniform crossover
			IntWritable[] ind1 = ind[0].getArray();
			IntWritable[] ind2 = ind[1].getArray();
			IntWritable[] newInd1 = new IntWritable[num_of_features];
			IntWritable[] newInd2 = new IntWritable[num_of_features];

			for (int i = 0; i < num_of_features; i++) {
				int i1 = 0, i2 = 0, mask = 1;
				// MUTATION
				if (rng.nextDouble() > 0.5) {
					i2 |= ind2[i].get() & mask;
					i1 |= ind1[i].get() & mask;
				} else {
					i1 |= ind2[i].get() & mask;
					i2 |= ind1[i].get() & mask;
				}
				mask = mask << 1;

				newInd1[i] = new IntWritable(i1);
				newInd2[i] = new IntWritable(i2);
			}

			ind[0] = new IntArrayWritable(newInd1);
			ind[1] = new IntArrayWritable(newInd2);
		}

		IntWritable[] tournament(int startIndex) {
			// Tournament selection without replacement
			IntWritable[] tournamentWinner = null;
			float tournamentMaxFitness = -1;
			for (int j = 0; j < tournamentSize; j++) {
				if (tournamentFitness[j] > tournamentMaxFitness) {
					tournamentMaxFitness = tournamentFitness[j];
					tournamentWinner = tournamentInd[j];
				}
			}
			return tournamentWinner;
		}

		OutputCollector<IntArrayWritable, FloatWritable> _output;

		public void reduce(IntArrayWritable key, Iterator<FloatWritable> values,
				OutputCollector<IntArrayWritable, FloatWritable> output, Reporter rep) throws IOException {
			// Save the output collector for later use
			_output = output;

			while (values.hasNext()) {
				float fitness = values.next().get();
				tournamentInd[processedIndividuals % tournamentSize] = key.getArray();
				tournamentFitness[processedIndividuals % tournamentSize] = fitness;

				if (processedIndividuals < tournamentSize) {
					// Wait for individuals to join in the tournament and put them for the last
					// round
					tournamentInd[processedIndividuals % tournamentSize + tournamentSize] = key.getArray();
					tournamentFitness[processedIndividuals % tournamentSize + tournamentSize] = fitness;
				} else {
					// Conduct a tournament over the past window
					ind[processedIndividuals % 2] = new IntArrayWritable(tournament(processedIndividuals));

					if ((processedIndividuals - tournamentSize) % 2 == 1) {
						// Do crossover for every odd iteration between successive
						// individuals
						crossover();
						output.collect(ind[0], new FloatWritable(0));
						output.collect(ind[1], new FloatWritable(0));
					}
				}
				processedIndividuals++;
			}
			if (processedIndividuals > 9) {
				closeAndWrite();
			}
		}

		public void closeAndWrite() {
			// Cleanup for the last window of tournament
			for (int k = 0; k < tournamentSize; k++) {
				// Conduct a tournament over the past window
				ind[processedIndividuals % 2] = new IntArrayWritable(tournament(processedIndividuals));

				if ((processedIndividuals - tournamentSize) % 2 == 1) {
					// Do crossover every odd iteration between successive
					// individuals
					crossover();
					try {
						_output.collect(ind[0], new FloatWritable(0));
						_output.collect(ind[1], new FloatWritable(0));
					} catch (IOException e) {
						System.err.println("Exception in collector of reducer");
						e.printStackTrace();
					}
				}
				processedIndividuals++;
			}
		}
	}

	// User-defined Partitioner
	@SuppressWarnings("hiding")
	public static class IndividualPartitioner<IntArrayWritable, FloatWritable>
			implements Partitioner<IntArrayWritable, FloatWritable> {

		// Partitions randomly independent of the passed <K, V>
		Random rng;

		public void configure(JobConf arg0) {
			rng = new Random(System.nanoTime());
		}

		public int getPartition(IntArrayWritable arg0, FloatWritable arg1, int numReducers) {
			return (Math.abs(rng.nextInt()) % numReducers);
		}
	}

	void launch(int numMaps, int numReducers, String jt, String dfs, String algorithm)
			throws IOException, InterruptedException, URISyntaxException {
		int num_of_features = 760;
		int it = 0;
		File output_file = new File("output.txt");
		BufferedWriter outputWriter = new BufferedWriter(new FileWriter(output_file));
		outputWriter.append("Start\n");
		outputWriter.flush();
		ArrayList<String> fitnesses = new ArrayList<String>();
		while (true) {
			JobConf jobConf = new JobConf(getConf(), MapReduce.class);
			// Set the Job properties
			jobConf.setSpeculativeExecution(true);
			jobConf.setInputFormat(SequenceFileInputFormat.class);
			jobConf.setOutputKeyClass(IntArrayWritable.class);
			jobConf.setOutputValueClass(FloatWritable.class);
			jobConf.setOutputFormat(SequenceFileOutputFormat.class);
			jobConf.set("ga.number_of_features", num_of_features + "");
			jobConf.setNumMapTasks(numMaps);
			jobConf.setPartitionerClass(IndividualPartitioner.class);
			jobConf.setJobName("ga-mr-" + it);
			if (jt != null) {
				jobConf.set("mapred.job.tracker", jt);
			}
			if (dfs != null) {
				FileSystem.setDefaultUri(jobConf, dfs);
			}

			System.out.println("launching");

			// Declare the directories
			Path tmpDir = new Path("/akshank/GA");
			Path inDir = new Path(tmpDir, "iter" + it);
			Path outDir = new Path(tmpDir, "iter" + (it + 1));
			FileInputFormat.setInputPaths(jobConf, inDir);
			FileOutputFormat.setOutputPath(jobConf, outDir);

			FileSystem fileSys = null;
			try {
				fileSys = FileSystem.get(jobConf);
			} catch (IOException e1) {
				e1.printStackTrace();
			}
			int populationPerMapper = 5 / numMaps;
			jobConf.set("ga.populationPerMapper", populationPerMapper + "");

			if (it == 0) {
				// Initialization of population
				try {
					fileSys.delete(tmpDir, true);
				} catch (IOException ie) {
					System.out.println("Exception while deleting");
					ie.printStackTrace();
				}
				System.out.println("Deleting dir");

				for (int i = 0; i < numMaps; ++i) {
					Path file = new Path(inDir, "part-" + String.format("%05d", i));
					SequenceFile.Writer writer = null;
					try {
						writer = SequenceFile.createWriter(fileSys, jobConf, file, IntArrayWritable.class,
								FloatWritable.class, CompressionType.NONE);
					} catch (Exception e) {
						System.out.println("Exception while instantiating writer");
						e.printStackTrace();
					}

					// Generate dummy input for all mappers
					IntWritable[] individual = new IntWritable[1];
					individual[0] = new IntWritable(populationPerMapper);
					try {
						writer.append(new IntArrayWritable(individual), new FloatWritable(0));
					} catch (Exception e) {
						System.out.println("Exception while appending to writer");
						e.printStackTrace();
					}
					try {
						writer.close();
					} catch (Exception e) {
						System.out.println("Exception while closing writer");
						e.printStackTrace();
					}
					System.out.println("Writing dummy input for Map #" + i);
				}
				jobConf.setMapperClass(InitializerMapper.class);
				jobConf.setReducerClass(IdentityReducer.class);
				jobConf.setNumReduceTasks(0);
			} // End of if it == 0 (end of initialization)
			else {
				System.out.println("\n\n\nITERATION " + it + " started\n\n\n");
				for (int i = 0; i < numMaps; ++i) {
					Option filePath = SequenceFile.Reader.file(new Path(inDir, "part-" + String.format("%05d", i)));
					SequenceFile.Reader sequenceFileReader = null;
					try {
						sequenceFileReader = new SequenceFile.Reader(jobConf, filePath);
					} catch (java.io.FileNotFoundException e) {
						break;
					}
					IntArrayWritable key = (IntArrayWritable) ReflectionUtils
							.newInstance(sequenceFileReader.getKeyClass(), jobConf);
					FloatWritable value = (FloatWritable) ReflectionUtils
							.newInstance(sequenceFileReader.getValueClass(), jobConf);

					Path file = new Path(inDir, "part-" + String.format("%05d", i));
					SequenceFile.Writer writer = SequenceFile.createWriter(fileSys, jobConf, file,
							IntArrayWritable.class, FloatWritable.class, CompressionType.NONE);

					ArrayList<IntArrayWritable> array = new ArrayList<IntArrayWritable>();

					while (sequenceFileReader.next(key, value)) {
						if (it != 1) {
							if (value.get() != 0) {
								array.add(key);
							}
						} else {
							array.add(key);
						}
						key = (IntArrayWritable) ReflectionUtils.newInstance(sequenceFileReader.getKeyClass(), jobConf);
						value = (FloatWritable) ReflectionUtils.newInstance(sequenceFileReader.getValueClass(),
								jobConf);
					}
					for (int j = 0; j < array.size(); j++) {
						if (array.get(j).toString().indexOf("1") == -1) {
							continue;
						}
						ProcessBuilder pb = null;
						if (algorithm.startsWith("d")) {
							pb = new ProcessBuilder("spark-submit", "./decision_tree.py", array.get(j).toString());
						}
						if (algorithm.startsWith("l")) {
							pb = new ProcessBuilder("spark-submit", "./logistic.py", array.get(j).toString());
						}
						pb.redirectErrorStream(true);
						Process process = pb.start();
						InputStreamReader output_stream = new InputStreamReader(process.getInputStream());
						BufferedReader reader = new BufferedReader(output_stream);
						String line = null;
						String fitness = "";
						while ((line = reader.readLine()) != null) {
							if (line.startsWith("0.")) {
								fitness = line;
							}
						}
						process.waitFor();
						System.out
								.println("\nIndividual = " + array.get(j).toString() + "\nFitness = " + fitness + "\n");
						try {
							writer.append(array.get(j), new FloatWritable(Float.parseFloat(fitness)));
						} catch (NumberFormatException e) {
							continue;
						}
					}
					sequenceFileReader.close();
					writer.close();
				}
				jobConf.setMapperClass(GAMapper.class);
				try {
					fileSys.delete(outDir, true);
					fileSys.delete(new Path(tmpDir, "global-map"), true);
				} catch (IOException ie) {
					System.out.println("Exception while deleting");
					ie.printStackTrace();
				}
			}
			try {
				JobClient.runJob(jobConf);
			} catch (IOException e) {
				System.out.println("Exception while running job");
				e.printStackTrace();
			}
			if (it != 0) {
				// End of a Genetic algorithm phase
				FloatWritable max = new FloatWritable();
				IntArrayWritable maxInd = new IntArrayWritable();
				FloatWritable finalMax = new FloatWritable(-1);
				IntArrayWritable finalInd = null;

				Path global = new Path(tmpDir, "global-map");

				FileStatus[] fs = null;
				SequenceFile.Reader reader = null;
				try {
					fs = fileSys.listStatus(global);
				} catch (IOException e) {
					System.out.println("Exception while instantiating reader in find winner");
					e.printStackTrace();
				}

				for (int i = 0; i < fs.length; i++) {
					Path inFile = fs[i].getPath();
					try {
						reader = new SequenceFile.Reader(fileSys, inFile, jobConf);
					} catch (IOException e) {
						System.out.println("Exception while instantiating reader");
						e.printStackTrace();
					}

					try {
						while (reader.next(maxInd, max)) {
							if (max.get() > finalMax.get()) {
								finalMax = max;
								finalInd = maxInd;
							}
						}
					} catch (IOException e) {
						System.out.println("Exception while reading from reader");
						e.printStackTrace();
					}
					try {
						reader.close();
					} catch (IOException e) {
						System.out.println("Exception while closing reader");
						e.printStackTrace();
					}
				}
				System.out.println("\n\nIteration = " + it + "\nFinal Individual - " + finalInd + "\n" + "Accuracy = "
						+ finalMax + "\n\n\n");
				fitnesses.add(finalMax + "");
				outputWriter.write("\n\nIteration = " + it + "\nFinal Individual - " + finalInd + "\n" + "Accuracy = "
						+ finalMax + "\n\n\n");
				outputWriter.flush();
			}
			it++;
			if (it == 15) {
				String max = fitnesses.get(fitnesses.size() - 1);
				if (max.contentEquals(fitnesses.get(fitnesses.size() - 2))) {
					if (max.contentEquals(fitnesses.get(fitnesses.size() - 3))) {
						if (max.contentEquals(fitnesses.get(fitnesses.size() - 4))) {
							if (max.contentEquals(fitnesses.get(fitnesses.size() - 5))) {
								outputWriter.close();
								break;
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Launches all the tasks in order.
	 */
	public int run(String[] args) throws Exception {
		if (args.length != 3) {
			System.err.println("Usage: GeneticMR <nMaps> <nReducers> <algorithm>");
			ToolRunner.printGenericCommandUsage(System.err);
			return -1;
		}

		// Set the command-line parameters
		int nMaps = Integer.parseInt(args[0]);
		int nReducers = Integer.parseInt(args[1]);
		String algorithm = args[2];

		launch(nMaps, nReducers, null, null, algorithm);

		return 0;
	}

	public static void main(String[] argv) throws Exception {
		int res = ToolRunner.run(new Configuration(), new MapReduce(), argv);
		System.exit(res);
	}
}