/*
 * Copyright (c) 2023 Villu Ruusmann
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
package sklearn2pmml.expression;

import java.util.Collections;
import java.util.List;

import org.dmg.pmml.MiningFunction;
import org.dmg.pmml.regression.RegressionModel;
import org.dmg.pmml.regression.RegressionTable;
import org.jpmml.converter.ContinuousLabel;
import org.jpmml.converter.Feature;
import org.jpmml.converter.FieldNameUtil;
import org.jpmml.converter.ModelUtil;
import org.jpmml.converter.PMMLEncoder;
import org.jpmml.converter.Schema;
import org.jpmml.converter.regression.RegressionModelUtil;
import org.jpmml.python.DataFrameScope;
import org.jpmml.python.Scope;
import sklearn.Regressor;
import sklearn2pmml.util.EvaluatableUtil;
import sklearn2pmml.util.Expression;

public class ExpressionRegressor extends Regressor {

	public ExpressionRegressor(String module, String name){
		super(module, name);
	}

	@Override
	public RegressionModel encodeModel(Schema schema){
		Expression expr = getExpr();

		PMMLEncoder encoder = schema.getEncoder();

		ContinuousLabel continuousLabel = (ContinuousLabel)schema.getLabel();
		List<? extends Feature> features = schema.getFeatures();

		Scope scope = new DataFrameScope("X", features, encoder);

		org.dmg.pmml.Expression pmmlExpression = EvaluatableUtil.translateExpression(expr, scope);

		Feature exprFeature = ExpressionUtil.toFeature(FieldNameUtil.create("expression"), pmmlExpression, encoder);

		RegressionTable regressionTable = RegressionModelUtil.createRegressionTable(Collections.singletonList(exprFeature), Collections.singletonList(1d), 0d);

		RegressionModel regressionModel = new RegressionModel(MiningFunction.REGRESSION, ModelUtil.createMiningSchema(continuousLabel), null)
			.setNormalizationMethod(RegressionModel.NormalizationMethod.NONE)
			.addRegressionTables(regressionTable);

		return regressionModel;
	}

	public Expression getExpr(){
		return get("expr", Expression.class);
	}
}