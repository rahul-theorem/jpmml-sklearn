/*
 * Copyright (c) 2015 Villu Ruusmann
 *
 * This file is part of JPMML-SkLearn
 *
 * JPMML-SkLearn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-SkLearn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-SkLearn.  If not, see <http://www.gnu.org/licenses/>.
 */
package sklearn.tree;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.primitives.Doubles;
import numpy.core.ScalarUtil;
import org.dmg.pmml.DataType;
import org.dmg.pmml.HasExtensions;
import org.dmg.pmml.MiningFunction;
import org.dmg.pmml.Model;
import org.dmg.pmml.Predicate;
import org.dmg.pmml.ScoreDistribution;
import org.dmg.pmml.SimplePredicate;
import org.dmg.pmml.True;
import org.dmg.pmml.Visitor;
import org.dmg.pmml.VisitorAction;
import org.dmg.pmml.mining.MiningModel;
import org.dmg.pmml.mining.Segment;
import org.dmg.pmml.mining.Segmentation;
import org.dmg.pmml.tree.BranchNode;
import org.dmg.pmml.tree.ClassifierNode;
import org.dmg.pmml.tree.LeafNode;
import org.dmg.pmml.tree.Node;
import org.dmg.pmml.tree.NodeTransformer;
import org.dmg.pmml.tree.SimplifyingNodeTransformer;
import org.dmg.pmml.tree.TreeModel;
import org.jpmml.converter.BinaryFeature;
import org.jpmml.converter.CategoricalLabel;
import org.jpmml.converter.CategoryManager;
import org.jpmml.converter.ContinuousFeature;
import org.jpmml.converter.Feature;
import org.jpmml.converter.ModelUtil;
import org.jpmml.converter.PredicateManager;
import org.jpmml.converter.Schema;
import org.jpmml.converter.ScoreDistributionManager;
import org.jpmml.converter.ThresholdFeature;
import org.jpmml.converter.ThresholdFeatureUtil;
import org.jpmml.converter.ValueUtil;
import org.jpmml.converter.visitors.AbstractExtender;
import org.jpmml.model.UnsupportedElementException;
import org.jpmml.model.visitors.AbstractVisitor;
import org.jpmml.python.ClassDictUtil;
import sklearn.Estimator;
import sklearn.HasEstimatorEnsemble;
import sklearn.tree.visitors.TreeModelCompactor;
import sklearn.tree.visitors.TreeModelFlattener;
import sklearn.tree.visitors.TreeModelPruner;

public class TreeUtil {

	private TreeUtil(){
	}

	static
	public <E extends Estimator & HasTreeOptions, M extends Model> M transform(E estimator, M model){
		Boolean winnerId = (Boolean)estimator.getOption(HasTreeOptions.OPTION_WINNER_ID, Boolean.FALSE);

		Map<String, Map<Integer, ?>> nodeExtensions = (Map)estimator.getOption(HasTreeOptions.OPTION_NODE_EXTENSIONS, null);
		Boolean nodeId = (Boolean)estimator.getOption(HasTreeOptions.OPTION_NODE_ID, winnerId);
		Boolean nodeScore = (Boolean)estimator.getOption(HasTreeOptions.OPTION_NODE_SCORE, winnerId ? Boolean.TRUE : null);

		boolean fixed = ((nodeExtensions != null) || (nodeId != null && nodeId) || (nodeScore != null && nodeScore));

		Boolean compact = (Boolean)estimator.getOption(HasTreeOptions.OPTION_COMPACT, fixed ? Boolean.FALSE : Boolean.TRUE);
		Boolean flat = (Boolean)estimator.getOption(HasTreeOptions.OPTION_FLAT, Boolean.FALSE);
		Boolean prune = (Boolean)estimator.getOption(HasTreeOptions.OPTION_PRUNE, fixed ? Boolean.FALSE : Boolean.TRUE);

		if(compact || flat || prune){

			if(fixed){
				throw new IllegalArgumentException("Conflicting tree model options");
			}

			// Activate defaults
			nodeExtensions = null;
			nodeId = winnerId;
			nodeScore = (winnerId ? Boolean.TRUE : null);
		} // End if

		if((Boolean.TRUE).equals(winnerId)){
			encodeNodeId(estimator, model);
		}

		List<Visitor> visitors = new ArrayList<>();

		// Prune first, in order to make the tree model smaller for subsequent transformers
		if((Boolean.TRUE).equals(prune)){
			visitors.add(new TreeModelPruner());
		} // End if

		if((Boolean.TRUE).equals(compact)){
			visitors.add(new TreeModelCompactor());
		} // End if

		if((Boolean.TRUE).equals(flat)){
			visitors.add(new TreeModelFlattener());
		} // End if

		if(nodeExtensions != null){
			Collection<? extends Map.Entry<String, Map<Integer, ?>>> entries = nodeExtensions.entrySet();

			for(Map.Entry<String, Map<Integer, ?>> entry : entries){
				String name = entry.getKey();
				Map<Integer, ?> values = entry.getValue();

				Visitor nodeExtender = new AbstractExtender(name){

					private NodeTransformer nodeTransformer = SimplifyingNodeTransformer.INSTANCE;


					@Override
					public VisitorAction visit(TreeModel treeModel){
						treeModel.setNode(ensureExtensibility(treeModel.getNode()));

						return super.visit(treeModel);
					}

					@Override
					public VisitorAction visit(Node node){

						if(node.hasNodes()){
							List<Node> children = node.getNodes();

							for(ListIterator<Node> childIt = children.listIterator(); childIt.hasNext(); ){
								childIt.set(ensureExtensibility(childIt.next()));
							}
						}

						Object value = getValue(node);
						if(value != null){
							value = ScalarUtil.decode(value);

							addExtension((Node & HasExtensions)node, ValueUtil.asString(value));
						}

						return super.visit(node);
					}

					private Node ensureExtensibility(Node node){

						if(node instanceof HasExtensions){
							return node;
						}

						Object value = getValue(node);
						if(value != null){
							return this.nodeTransformer.toComplexNode(node);
						}

						return node;
					}

					private Object getValue(Node node){
						Integer id = ValueUtil.asInteger((Number)node.getId());

						return values.get(id);
					}
				};

				visitors.add(nodeExtender);
			}
		} // End if

		if((Boolean.FALSE).equals(nodeId)){
			Visitor nodeIdCleaner = new AbstractVisitor(){

				@Override
				public VisitorAction visit(Node node){
					node.setId(null);

					return super.visit(node);
				}
			};

			visitors.add(nodeIdCleaner);
		} // End if

		if((Boolean.FALSE).equals(nodeScore)){
			Visitor nodeScoreCleaner = new AbstractVisitor(){

				@Override
				public VisitorAction visit(Node node){

					if(node.hasNodes()){
						node.setScore(null);

						if(node.hasScoreDistributions()){
							List<ScoreDistribution> scoreDistributions = node.getScoreDistributions();

							scoreDistributions.clear();
						}
					}

					return super.visit(node);
				}
			};

			visitors.add(nodeScoreCleaner);
		}

		for(Visitor visitor : visitors){
			visitor.applyTo(model);
		}

		return model;
	}

	static
	public <E extends Estimator & HasEstimatorEnsemble<T>, T extends Estimator & HasTree> List<TreeModel> encodeTreeModelEnsemble(E estimator, MiningFunction miningFunction, Schema schema){
		Boolean numeric = (Boolean)estimator.getOption(HasTreeOptions.OPTION_NUMERIC, Boolean.TRUE);

		PredicateManager predicateManager = new PredicateManager();
		ScoreDistributionManager scoreDistributionManager = new ScoreDistributionManager();

		return encodeTreeModelEnsemble(estimator, miningFunction, numeric, predicateManager, scoreDistributionManager, schema);
	}

	static
	public <E extends Estimator & HasEstimatorEnsemble<T>, T extends Estimator & HasTree> List<TreeModel> encodeTreeModelEnsemble(E estimator, MiningFunction miningFunction, Boolean numeric, PredicateManager predicateManager, ScoreDistributionManager scoreDistributionManager, Schema schema){
		List<? extends T> estimators = estimator.getEstimators();

		Schema segmentSchema = schema.toAnonymousSchema();

		Function<T, TreeModel> function = new Function<T, TreeModel>(){

			@Override
			public TreeModel apply(T estimator){
				Schema treeModelSchema = toTreeModelSchema(estimator.getDataType(), numeric, segmentSchema);

				TreeModel treeModel = TreeUtil.encodeTreeModel(estimator, miningFunction, numeric, predicateManager, scoreDistributionManager, treeModelSchema);

				// XXX
				if(estimator.hasFeatureImportances()){
					Schema featureImportanceSchema = toTreeModelFeatureImportanceSchema(numeric, treeModelSchema);

					estimator.addFeatureImportances(treeModel, featureImportanceSchema);
				}

				return treeModel;
			}
		};

		return estimators.stream()
			.map(function)
			.collect(Collectors.toList());
	}

	static
	public <E extends Estimator & HasTree> TreeModel encodeTreeModel(E estimator, MiningFunction miningFunction, Schema schema){
		Boolean numeric = (Boolean)estimator.getOption(HasTreeOptions.OPTION_NUMERIC, Boolean.TRUE);

		PredicateManager predicateManager = new PredicateManager();
		ScoreDistributionManager scoreDistributionManager = new ScoreDistributionManager();

		return encodeTreeModel(estimator, miningFunction, numeric, predicateManager, scoreDistributionManager, schema);
	}

	static
	public <E extends Estimator & HasTree> TreeModel encodeTreeModel(E estimator, MiningFunction miningFunction, Boolean numeric, PredicateManager predicateManager, ScoreDistributionManager scoreDistributionManager, Schema schema){
		Tree tree = estimator.getTree();

		int[] leftChildren = tree.getChildrenLeft();
		int[] rightChildren = tree.getChildrenRight();
		int[] features = tree.getFeature();
		double[] thresholds = tree.getThreshold();
		double[] values = tree.getValues();

		Node root = encodeNode(0, True.INSTANCE, miningFunction, numeric, leftChildren, rightChildren, features, thresholds, values, new CategoryManager(), predicateManager, scoreDistributionManager, schema);

		TreeModel treeModel = new TreeModel(miningFunction, ModelUtil.createMiningSchema(schema.getLabel()), root)
			.setSplitCharacteristic(TreeModel.SplitCharacteristic.BINARY_SPLIT);

		ClassDictUtil.clearContent(tree);

		return treeModel;
	}

	static
	private Node encodeNode(int index, Predicate predicate, MiningFunction miningFunction, boolean numeric, int[] leftChildren, int[] rightChildren, int[] features, double[] thresholds, double[] values, CategoryManager categoryManager, PredicateManager predicateManager, ScoreDistributionManager scoreDistributionManager, Schema schema){
		Integer id = Integer.valueOf(index);

		int featureIndex = features[index];

		// A non-leaf (binary split) node
		if(featureIndex >= 0){
			Feature feature = schema.getFeature(featureIndex);

			double threshold = thresholds[index];

			CategoryManager leftCategoryManager = categoryManager;
			CategoryManager rightCategoryManager = categoryManager;

			Predicate leftPredicate;
			Predicate rightPredicate;

			if(feature instanceof BinaryFeature){
				BinaryFeature binaryFeature = (BinaryFeature)feature;

				if(threshold < 0 || threshold > 1){
					throw new IllegalArgumentException();
				}

				Object value = binaryFeature.getValue();

				leftPredicate = predicateManager.createSimplePredicate(binaryFeature, SimplePredicate.Operator.NOT_EQUAL, value);
				rightPredicate = predicateManager.createSimplePredicate(binaryFeature, SimplePredicate.Operator.EQUAL, value);
			} else

			if(feature instanceof ThresholdFeature && !numeric){
				ThresholdFeature thresholdFeature = (ThresholdFeature)feature;

				String name = thresholdFeature.getName();

				Object missingValue = thresholdFeature.getMissingValue();

				java.util.function.Predicate<Object> valueFilter = categoryManager.getValueFilter(name);

				if(!ValueUtil.isNaN(missingValue)){
					valueFilter = valueFilter.and(value -> !ValueUtil.isNaN(value));
				}

				List<Object> leftValues = thresholdFeature.getValues((Number value) -> (toSplitValue(value) <= threshold)).stream()
					.filter(valueFilter)
					.collect(Collectors.toList());

				List<Object> rightValues = thresholdFeature.getValues((Number value) -> (toSplitValue(value)) > threshold).stream()
					.filter(valueFilter)
					.collect(Collectors.toList());

				leftCategoryManager = leftCategoryManager.fork(name, leftValues);
				rightCategoryManager = rightCategoryManager.fork(name, rightValues);

				leftPredicate = ThresholdFeatureUtil.createPredicate(thresholdFeature, leftValues, missingValue, predicateManager);
				rightPredicate = ThresholdFeatureUtil.createPredicate(thresholdFeature, rightValues, missingValue, predicateManager);
			} else

			{
				ContinuousFeature continuousFeature = toContinuousFeature(feature);

				Double value = threshold;

				leftPredicate = predicateManager.createSimplePredicate(continuousFeature, SimplePredicate.Operator.LESS_OR_EQUAL, value);
				rightPredicate = predicateManager.createSimplePredicate(continuousFeature, SimplePredicate.Operator.GREATER_THAN, value);
			}

			int leftIndex = leftChildren[index];
			int rightIndex = rightChildren[index];

			Node leftChild = encodeNode(leftIndex, leftPredicate, miningFunction, numeric, leftChildren, rightChildren, features, thresholds, values, leftCategoryManager, predicateManager, scoreDistributionManager, schema);
			Node rightChild = encodeNode(rightIndex, rightPredicate, miningFunction, numeric, leftChildren, rightChildren, features, thresholds, values, rightCategoryManager, predicateManager, scoreDistributionManager, schema);

			Node result;

			if(miningFunction == MiningFunction.CLASSIFICATION){
				result = new ClassifierNode(null, predicate);
			} else

			if(miningFunction == MiningFunction.REGRESSION){
				double value = values[index];

				result = new BranchNode(value, predicate);
			} else

			{
				throw new IllegalArgumentException();
			}

			result
				.setId(id)
				.addNodes(leftChild, rightChild);

			return result;
		} else

		// A leaf node
		{
			Node result;

			if(miningFunction == MiningFunction.CLASSIFICATION){
				CategoricalLabel categoricalLabel = (CategoricalLabel)schema.getLabel();

				double[] leafValues = getRow(values, leftChildren.length, categoricalLabel.size(), index);

				List<Number> recordCounts = new AbstractList<Number>(){

					@Override
					public int size(){
						return leafValues.length;
					}

					@Override
					public Number get(int index){
						double leafValue = leafValues[index];

						return ValueUtil.narrow(leafValue);
					}
				};

				double totalRecordCount = 0d;

				for(Number recordCount : recordCounts){
					totalRecordCount += recordCount.doubleValue();
				}

				// XXX
				int maxIndex = ScoreDistributionManager.indexOfMax(Doubles.asList(leafValues));

				Object score = categoricalLabel.getValue(maxIndex);

				result = new ClassifierNode(score, predicate)
					.setId(id)
					.setRecordCount(ValueUtil.narrow(totalRecordCount));

				scoreDistributionManager.addScoreDistributions(result, categoricalLabel.getValues(), recordCounts, null);
			} else

			if(miningFunction == MiningFunction.REGRESSION){
				double value = values[index];

				result = new LeafNode(value, predicate)
					.setId(id);
			} else

			{
				throw new IllegalArgumentException();
			}

			return result;
		}
	}

	static
	private void encodeNodeId(Estimator estimator, Model model){

		if(model instanceof TreeModel){
			TreeModel treeModel = (TreeModel)model;

			estimator.encodeApplyOutput(treeModel, DataType.INTEGER);
		} else

		if(model instanceof MiningModel){
			MiningModel miningModel = (MiningModel)model;

			Segmentation segmentation = miningModel.requireSegmentation();

			List<Segment> segments = segmentation.requireSegments();

			List<String> segmentIds = new ArrayList<>();

			for(Segment segment : segments){
				TreeModel treeModel = segment.requireModel(TreeModel.class);

				String segmentId = segment.getId();
				if(segmentId == null){
					throw new UnsupportedElementException(segment);
				}

				segmentIds.add(segmentId);
			}

			estimator.encodeMultiApplyOutput(miningModel, DataType.INTEGER, segmentIds);
		} else

		{
			throw new IllegalArgumentException();
		}
	}

	static
	private Schema toTreeModelSchema(DataType dataType, boolean numeric, Schema schema){
		Function<Feature, Feature> function = new Function<Feature, Feature>(){

			@Override
			public Feature apply(Feature feature){

				if(feature instanceof BinaryFeature){
					BinaryFeature binaryFeature = (BinaryFeature)feature;

					return binaryFeature;
				} else

				if(feature instanceof ThresholdFeature && !numeric){
					ThresholdFeature thresholdFeature = (ThresholdFeature)feature;

					return thresholdFeature;
				} else

				{
					ContinuousFeature continuousFeature = feature.toContinuousFeature(dataType);

					return continuousFeature;
				}
			}
		};

		return schema.toTransformedSchema(function);
	}

	static
	private Schema toTreeModelFeatureImportanceSchema(boolean numeric, Schema schema){
		Function<Feature, Feature> function = new Function<Feature, Feature>(){

			@Override
			public Feature apply(Feature feature){

				if(feature instanceof BinaryFeature){
					BinaryFeature binaryFeature = (BinaryFeature)feature;

					return binaryFeature;
				} else

				if(feature instanceof ThresholdFeature && !numeric){
					ThresholdFeature thresholdFeature = (ThresholdFeature)feature;

					return thresholdFeature;
				} else

				{
					ContinuousFeature continuousFeature = toContinuousFeature(feature);

					return continuousFeature;
				}
			}
		};

		return schema.toTransformedSchema(function);
	}

	static
	private ContinuousFeature toContinuousFeature(Feature feature){
		return feature
			.toContinuousFeature(DataType.FLOAT) // First, cast from any numeric type (including numpy.float64) to numpy.float32
			.toContinuousFeature(DataType.DOUBLE); // Second, cast from numpy.float32 to numpy.float64
	}

	static
	private double toSplitValue(Number value){
		return (double)value.floatValue();
	}

	static
	private double[] getRow(double[] values, int rows, int columns, int row){

		if(values.length != (rows * columns)){
			throw new IllegalArgumentException("Expected " + (rows * columns) + " element(s), got " + values.length + " element(s)");
		}

		double[] result = new double[columns];

		System.arraycopy(values, (row * columns), result, 0, columns);

		return result;
	}
}
