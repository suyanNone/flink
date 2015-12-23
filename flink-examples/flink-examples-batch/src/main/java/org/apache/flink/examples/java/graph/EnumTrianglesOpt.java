/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.examples.java.graph;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.GroupReduceFunction;
import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.operators.Order;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.functions.FunctionAnnotation.ForwardedFields;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.examples.java.graph.util.EnumTrianglesData;
import org.apache.flink.examples.java.graph.util.EnumTrianglesDataTypes;
import org.apache.flink.util.Collector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Triangle enumeration is a pre-processing step to find closely connected parts in graphs.
 * A triangle consists of three edges that connect three vertices with each other.
 * 
 * <p>
 * The basic algorithm works as follows: 
 * It groups all edges that share a common vertex and builds triads, i.e., triples of vertices 
 * that are connected by two edges. Finally, all triads are filtered for which no third edge exists 
 * that closes the triangle.
 * 
 * <p>
 * For a group of <i>n</i> edges that share a common vertex, the number of built triads is quadratic <i>((n*(n-1))/2)</i>.
 * Therefore, an optimization of the algorithm is to group edges on the vertex with the smaller output degree to 
 * reduce the number of triads. 
 * This implementation extends the basic algorithm by computing output degrees of edge vertices and 
 * grouping on edges on the vertex with the smaller degree.
 * 
 * <p>
 * Input files are plain text files and must be formatted as follows:
 * <ul>
 * <li>Edges are represented as pairs for vertex IDs which are separated by space 
 * characters. Edges are separated by new-line characters.<br>
 * For example <code>"1 2\n2 12\n1 12\n42 63"</code> gives four (undirected) edges (1)-(2), (2)-(12), (1)-(12), and (42)-(63)
 * that include a triangle
 * </ul>
 * <pre>
 *     (1)
 *     /  \
 *   (2)-(12)
 * </pre>
 * 
 * Usage: <code>EnumTriangleOpt &lt;edge path&gt; &lt;result path&gt;</code><br>
 * If no parameters are provided, the program is run with default data from {@link org.apache.flink.examples.java.graph.util.EnumTrianglesData}.
 * 
 * <p>
 * This example shows how to use:
 * <ul>
 * <li>Custom Java objects which extend Tuple
 * <li>Group Sorting
 * </ul>
 * 
 */
@SuppressWarnings("serial")
public class EnumTrianglesOpt {

	// *************************************************************************
	//     PROGRAM
	// *************************************************************************
	
	public static void main(String[] args) throws Exception {
		
		if(!parseParameters(args)) {
			return;
		}
		
		// set up execution environment
		final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
		
		// read input data
		DataSet<EnumTrianglesDataTypes.Edge> edges = getEdgeDataSet(env);
		
		// annotate edges with degrees
		DataSet<EnumTrianglesDataTypes.EdgeWithDegrees> edgesWithDegrees = edges
				.flatMap(new EdgeDuplicator())
				.groupBy(EnumTrianglesDataTypes.Edge.V1).sortGroup(EnumTrianglesDataTypes.Edge.V2, Order.ASCENDING).reduceGroup(new DegreeCounter())
				.groupBy(EnumTrianglesDataTypes.EdgeWithDegrees.V1, EnumTrianglesDataTypes.EdgeWithDegrees.V2).reduce(new DegreeJoiner());
		
		// project edges by degrees
		DataSet<EnumTrianglesDataTypes.Edge> edgesByDegree = edgesWithDegrees
				.map(new EdgeByDegreeProjector());
		// project edges by vertex id
		DataSet<EnumTrianglesDataTypes.Edge> edgesById = edgesByDegree
				.map(new EdgeByIdProjector());
		
		DataSet<EnumTrianglesDataTypes.Triad> triangles = edgesByDegree
				// build triads
				.groupBy(EnumTrianglesDataTypes.Edge.V1).sortGroup(EnumTrianglesDataTypes.Edge.V2, Order.ASCENDING).reduceGroup(new TriadBuilder())
				// filter triads
				.join(edgesById).where(EnumTrianglesDataTypes.Triad.V2, EnumTrianglesDataTypes.Triad.V3).equalTo(EnumTrianglesDataTypes.Edge.V1, EnumTrianglesDataTypes.Edge.V2).with(new TriadFilter());

		// emit result
		if(fileOutput) {
			triangles.writeAsCsv(outputPath, "\n", ",");
			// execute program
			env.execute("Triangle Enumeration Example");
		} else {
			triangles.print();
		}

		
	}
	
	// *************************************************************************
	//     USER FUNCTIONS
	// *************************************************************************
	
	/** Converts a Tuple2 into an Edge */
	@ForwardedFields("0;1")
	public static class TupleEdgeConverter implements MapFunction<Tuple2<Integer, Integer>, EnumTrianglesDataTypes.Edge> {
		private final EnumTrianglesDataTypes.Edge outEdge = new EnumTrianglesDataTypes.Edge();
		
		@Override
		public EnumTrianglesDataTypes.Edge map(Tuple2<Integer, Integer> t) throws Exception {
			outEdge.copyVerticesFromTuple2(t);
			return outEdge;
		}
	}
	
	/** Emits for an edge the original edge and its switched version. */
	private static class EdgeDuplicator implements FlatMapFunction<EnumTrianglesDataTypes.Edge, EnumTrianglesDataTypes.Edge> {
		
		@Override
		public void flatMap(EnumTrianglesDataTypes.Edge edge, Collector<EnumTrianglesDataTypes.Edge> out) throws Exception {
			out.collect(edge);
			edge.flipVertices();
			out.collect(edge);
		}
	}
	
	/**
	 * Counts the number of edges that share a common vertex.
	 * Emits one edge for each input edge with a degree annotation for the shared vertex.
	 * For each emitted edge, the first vertex is the vertex with the smaller id.
	 */
	private static class DegreeCounter implements GroupReduceFunction<EnumTrianglesDataTypes.Edge, EnumTrianglesDataTypes.EdgeWithDegrees> {
		
		final ArrayList<Integer> otherVertices = new ArrayList<Integer>();
		final EnumTrianglesDataTypes.EdgeWithDegrees outputEdge = new EnumTrianglesDataTypes.EdgeWithDegrees();
		
		@Override
		public void reduce(Iterable<EnumTrianglesDataTypes.Edge> edgesIter, Collector<EnumTrianglesDataTypes.EdgeWithDegrees> out) {
			
			Iterator<EnumTrianglesDataTypes.Edge> edges = edgesIter.iterator();
			otherVertices.clear();
			
			// get first edge
			EnumTrianglesDataTypes.Edge edge = edges.next();
			Integer groupVertex = edge.getFirstVertex();
			this.otherVertices.add(edge.getSecondVertex());
			
			// get all other edges (assumes edges are sorted by second vertex)
			while (edges.hasNext()) {
				edge = edges.next();
				Integer otherVertex = edge.getSecondVertex();
				// collect unique vertices
				if(!otherVertices.contains(otherVertex) && otherVertex != groupVertex) {
					this.otherVertices.add(otherVertex);
				}
			}
			int degree = this.otherVertices.size();
			
			// emit edges
			for(Integer otherVertex : this.otherVertices) {
				if(groupVertex < otherVertex) {
					outputEdge.setFirstVertex(groupVertex);
					outputEdge.setFirstDegree(degree);
					outputEdge.setSecondVertex(otherVertex);
					outputEdge.setSecondDegree(0);
				} else {
					outputEdge.setFirstVertex(otherVertex);
					outputEdge.setFirstDegree(0);
					outputEdge.setSecondVertex(groupVertex);
					outputEdge.setSecondDegree(degree);
				}
				out.collect(outputEdge);
			}
		}
	}
	
	/**
	 * Builds an edge with degree annotation from two edges that have the same vertices and only one 
	 * degree annotation.
	 */
	@ForwardedFields("0;1")
	private static class DegreeJoiner implements ReduceFunction<EnumTrianglesDataTypes.EdgeWithDegrees> {
		private final EnumTrianglesDataTypes.EdgeWithDegrees outEdge = new EnumTrianglesDataTypes.EdgeWithDegrees();
		
		@Override
		public EnumTrianglesDataTypes.EdgeWithDegrees reduce(EnumTrianglesDataTypes.EdgeWithDegrees edge1, EnumTrianglesDataTypes.EdgeWithDegrees edge2) throws Exception {
			
			// copy first edge
			outEdge.copyFrom(edge1);
			
			// set missing degree
			if(edge1.getFirstDegree() == 0 && edge1.getSecondDegree() != 0) {
				outEdge.setFirstDegree(edge2.getFirstDegree());
			} else if (edge1.getFirstDegree() != 0 && edge1.getSecondDegree() == 0) {
				outEdge.setSecondDegree(edge2.getSecondDegree());
			}
			return outEdge;
		}
	}
		
	/** Projects an edge (pair of vertices) such that the first vertex is the vertex with the smaller degree. */
	private static class EdgeByDegreeProjector implements MapFunction<EnumTrianglesDataTypes.EdgeWithDegrees, EnumTrianglesDataTypes.Edge> {
		
		private final EnumTrianglesDataTypes.Edge outEdge = new EnumTrianglesDataTypes.Edge();
		
		@Override
		public EnumTrianglesDataTypes.Edge map(EnumTrianglesDataTypes.EdgeWithDegrees inEdge) throws Exception {

			// copy vertices to simple edge
			outEdge.copyVerticesFromEdgeWithDegrees(inEdge);

			// flip vertices if first degree is larger than second degree.
			if(inEdge.getFirstDegree() > inEdge.getSecondDegree()) {
				outEdge.flipVertices();
			}

			// return edge
			return outEdge;
		}
	}
	
	/** Projects an edge (pair of vertices) such that the id of the first is smaller than the id of the second. */
	private static class EdgeByIdProjector implements MapFunction<EnumTrianglesDataTypes.Edge, EnumTrianglesDataTypes.Edge> {
	
		@Override
		public EnumTrianglesDataTypes.Edge map(EnumTrianglesDataTypes.Edge inEdge) throws Exception {
			
			// flip vertices if necessary
			if(inEdge.getFirstVertex() > inEdge.getSecondVertex()) {
				inEdge.flipVertices();
			}
			
			return inEdge;
		}
	}
	
	/**
	 *  Builds triads (triples of vertices) from pairs of edges that share a vertex.
	 *  The first vertex of a triad is the shared vertex, the second and third vertex are ordered by vertexId. 
	 *  Assumes that input edges share the first vertex and are in ascending order of the second vertex.
	 */
	@ForwardedFields("0")
	private static class TriadBuilder implements GroupReduceFunction<EnumTrianglesDataTypes.Edge, EnumTrianglesDataTypes.Triad> {
		
		private final List<Integer> vertices = new ArrayList<Integer>();
		private final EnumTrianglesDataTypes.Triad outTriad = new EnumTrianglesDataTypes.Triad();
		
		@Override
		public void reduce(Iterable<EnumTrianglesDataTypes.Edge> edgesIter, Collector<EnumTrianglesDataTypes.Triad> out) throws Exception {
			final Iterator<EnumTrianglesDataTypes.Edge> edges = edgesIter.iterator();
			
			// clear vertex list
			vertices.clear();
			
			// read first edge
			EnumTrianglesDataTypes.Edge firstEdge = edges.next();
			outTriad.setFirstVertex(firstEdge.getFirstVertex());
			vertices.add(firstEdge.getSecondVertex());
			
			// build and emit triads
			while (edges.hasNext()) {
				Integer higherVertexId = edges.next().getSecondVertex();
				
				// combine vertex with all previously read vertices
				for(Integer lowerVertexId : vertices) {
					outTriad.setSecondVertex(lowerVertexId);
					outTriad.setThirdVertex(higherVertexId);
					out.collect(outTriad);
				}
				vertices.add(higherVertexId);
			}
		}
	}
	
	/** Filters triads (three vertices connected by two edges) without a closing third edge. */
	private static class TriadFilter implements JoinFunction<EnumTrianglesDataTypes.Triad, EnumTrianglesDataTypes.Edge, EnumTrianglesDataTypes.Triad> {
		
		@Override
		public EnumTrianglesDataTypes.Triad join(EnumTrianglesDataTypes.Triad triad, EnumTrianglesDataTypes.Edge edge) throws Exception {
			return triad;
		}
	}
	
	// *************************************************************************
	//     UTIL METHODS
	// *************************************************************************
	
	private static boolean fileOutput = false;
	private static String edgePath = null;
	private static String outputPath = null;
	
	private static boolean parseParameters(String[] args) {
		
		if(args.length > 0) {
			// parse input arguments
			fileOutput = true;
			if(args.length == 2) {
				edgePath = args[0];
				outputPath = args[1];
			} else {
				System.err.println("Usage: EnumTriangleBasic <edge path> <result path>");
				return false;
			}
		} else {
			System.out.println("Executing Enum Triangles Opt example with built-in default data.");
			System.out.println("  Provide parameters to read input data from files.");
			System.out.println("  See the documentation for the correct format of input files.");
			System.out.println("  Usage: EnumTriangleOpt <edge path> <result path>");
		}
		return true;
	}
	
	private static DataSet<EnumTrianglesDataTypes.Edge> getEdgeDataSet(ExecutionEnvironment env) {
		if(fileOutput) {
			return env.readCsvFile(edgePath)
						.fieldDelimiter(" ")
						.includeFields(true, true)
						.types(Integer.class, Integer.class)
						.map(new TupleEdgeConverter());
		} else {
			return EnumTrianglesData.getDefaultEdgeDataSet(env);
		}
	}
	
}