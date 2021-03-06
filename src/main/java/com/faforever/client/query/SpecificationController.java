package com.faforever.client.query;

import com.faforever.client.fx.Controller;
import com.faforever.client.i18n.I18n;
import com.faforever.client.util.ProgrammingError;
import com.github.rutledgepaulv.qbuilders.builders.QBuilder;
import com.github.rutledgepaulv.qbuilders.conditions.Condition;
import com.github.rutledgepaulv.qbuilders.operators.ComparisonOperator;
import com.github.rutledgepaulv.qbuilders.properties.concrete.BooleanProperty;
import com.github.rutledgepaulv.qbuilders.properties.concrete.EnumProperty;
import com.github.rutledgepaulv.qbuilders.properties.concrete.InstantProperty;
import com.github.rutledgepaulv.qbuilders.properties.concrete.StringProperty;
import com.github.rutledgepaulv.qbuilders.properties.virtual.EquitableProperty;
import com.github.rutledgepaulv.qbuilders.properties.virtual.ListableProperty;
import com.github.rutledgepaulv.qbuilders.properties.virtual.NumberProperty;
import com.github.rutledgepaulv.qbuilders.properties.virtual.Property;
import com.google.common.collect.ImmutableMap;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;
import lombok.SneakyThrows;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.github.rutledgepaulv.qbuilders.operators.ComparisonOperator.EQ;
import static com.github.rutledgepaulv.qbuilders.operators.ComparisonOperator.GT;
import static com.github.rutledgepaulv.qbuilders.operators.ComparisonOperator.GTE;
import static com.github.rutledgepaulv.qbuilders.operators.ComparisonOperator.IN;
import static com.github.rutledgepaulv.qbuilders.operators.ComparisonOperator.LT;
import static com.github.rutledgepaulv.qbuilders.operators.ComparisonOperator.LTE;
import static com.github.rutledgepaulv.qbuilders.operators.ComparisonOperator.NE;
import static com.github.rutledgepaulv.qbuilders.operators.ComparisonOperator.NIN;

/**
 * Controller for building a specification in the sense of Domain Driven Design, e.g. {@code login == "Someone"} or
 * {@code rating > 500}. The specification can then be converted into a condition to be used in a {@link QBuilder}.
 * Before it can be used, the type of the object to query has to be defined first by using {@link #setRootType(Class)}.
 * This controller consists of a property ChoiceBox, an operator ChoiceBox and a value field. The items available in the
 * operator ChoiceBox depend on the selected property and its respective type in the target class.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SpecificationController implements Controller<Node> {

  private static final Map<ComparisonOperator, String> operatorToI18nKey = ImmutableMap.<ComparisonOperator, String>builder()
      .put(EQ, "query.equals")
      .put(NE, "query.notEquals")
      .put(GT, "query.greaterThan")
      .put(GTE, "query.greaterThanEquals")
      .put(LT, "query.lessThan")
      .put(LTE, "query.lessThanEquals")
      .put(IN, "query.in")
      .put(NIN, "query.notIn")
      .build();

  private static final Map<Class<?>, Collection<ComparisonOperator>> VALID_OPERATORS = ImmutableMap.of(
      Number.class, Arrays.asList(EQ, NE, GT, GTE, LT, LTE, IN, NIN),
      Temporal.class, Arrays.asList(EQ, NE, GT, GTE, LT, LTE),
      String.class, Arrays.asList(EQ, NE, IN, NIN),
      Boolean.class, Arrays.asList(EQ, NE),
      Enum.class, Arrays.asList(EQ, NE, IN, NIN)
  );

  private final I18n i18n;
  private final FilteredList<ComparisonOperator> comparisonOperators;
  public ComboBox<String> propertyField;
  public ChoiceBox<ComparisonOperator> operationField;
  public ComboBox<Object> valueField;
  public HBox specificationRoot;
  public DatePicker datePicker;
  private Class<?> rootType;
  private Map<String, String> properties;

  public SpecificationController(I18n i18n) {
    this.i18n = i18n;
    comparisonOperators = new FilteredList<>(FXCollections.observableArrayList(operatorToI18nKey.keySet()));
  }

  public void initialize() {
    datePicker.setValue(LocalDate.now());
    datePicker.managedProperty().bind(datePicker.visibleProperty());
    datePicker.setVisible(false);

    valueField.managedProperty().bind(valueField.visibleProperty());

    operationField.setItems(comparisonOperators);
    operationField.setConverter(new StringConverter<ComparisonOperator>() {
      @Override
      public String toString(ComparisonOperator object) {
        return i18n.get(operatorToI18nKey.get(object));
      }

      @Override
      public ComparisonOperator fromString(String string) {
        throw new UnsupportedOperationException("Not supported");
      }
    });

    propertyField.setConverter(new StringConverter<String>() {
      @Override
      public String toString(String object) {
        return i18n.get(properties.get(object));
      }

      @Override
      public String fromString(String string) {
        throw new UnsupportedOperationException("Not supported");
      }
    });
    propertyField.valueProperty().addListener((observable, oldValue, newValue) -> {
      comparisonOperators.setPredicate(comparisonOperator -> isOperatorApplicable(comparisonOperator, newValue));
      if (!isOperatorApplicable(operationField.getValue(), newValue)) {
        operationField.getSelectionModel().select(0);
      }
      populateValueField(getPropertyClass(newValue));
    });
  }

  /**
   * If there are predefined values for a property (like an enum or boolean), the value field is populated with the
   * possible values. If its an instant, a date picker will be displayed and the value field will be hidden. The
   * selected date will then be populated to the hidden value field in its proper format.
   */
  private void populateValueField(Class<?> propertyClass) {
    valueField.setVisible(true);
    valueField.getItems().clear();
    valueField.valueProperty().unbind();
    valueField.setValue(null);
    datePicker.setVisible(false);

    if (ClassUtils.isAssignable(Boolean.class, propertyClass)) {
      valueField.getItems().setAll(Boolean.TRUE, Boolean.FALSE);
      valueField.getSelectionModel().select(0);
    } else if (ClassUtils.isAssignable(Enum.class, propertyClass)) {
      valueField.getItems().setAll(propertyClass.getEnumConstants());
      valueField.getSelectionModel().select(0);
    } else if (ClassUtils.isAssignable(Temporal.class, propertyClass)) {
      datePicker.setVisible(true);
      valueField.setVisible(false);
      valueField.valueProperty()
          .bind(Bindings.createStringBinding(() -> datePicker.getValue().atStartOfDay(ZoneId.systemDefault())
              .format(DateTimeFormatter.ISO_INSTANT), datePicker.valueProperty()));
    }
  }

  private boolean isOperatorApplicable(ComparisonOperator comparisonOperator, String propertyName) {
    Class<?> propertyClass = ClassUtils.resolvePrimitiveIfNecessary(getPropertyClass(propertyName));

    for (Class<?> aClass : VALID_OPERATORS.keySet()) {
      if (aClass.isAssignableFrom(propertyClass)) {
        propertyClass = aClass;
      }
    }

    if (!VALID_OPERATORS.containsKey(propertyClass)) {
      throw new IllegalStateException("No valid operators specified for property: " + propertyName);
    }

    return VALID_OPERATORS.get(propertyClass).contains(comparisonOperator);
  }

  @Override
  public Node getRoot() {
    return specificationRoot;
  }

  /**
   * Sets the properties that can be queried. These must match the field names of the specified type.
   *
   * @see #setRootType(Class)
   */
  public void setProperties(Map<String, String> properties) {
    this.properties = properties;
    propertyField.setItems(FXCollections.observableList(new ArrayList<>(properties.keySet())));
    propertyField.getSelectionModel().select(0);
    operationField.getSelectionModel().select(0);
  }

  /**
   * Sets the type this controller can build queries for (including types used in relationships).
   */
  public void setRootType(Class<?> rootType) {
    this.rootType = rootType;
  }

  private Collection<Integer> splitInts(String value) {
    return Arrays.stream(value.split(","))
        .map(Integer::parseInt)
        .collect(Collectors.toList());
  }

  @SuppressWarnings("unchecked")
  public Optional<Condition> appendTo(QBuilder qBuilder) {
    String propertyName = propertyField.getValue();
    if (propertyName == null) {
      return Optional.empty();
    }

    ComparisonOperator comparisonOperator = operationField.getValue();
    if (comparisonOperator == null) {
      return Optional.empty();
    }

    String value = Optional.ofNullable(valueField.getValue()).map(String::valueOf).orElse("");
    if (value.isEmpty()) {
      return Optional.empty();
    }

    Class<?> propertyClass = getPropertyClass(propertyName);
    Property property = getProperty(qBuilder, propertyName, propertyClass);

    if (property instanceof NumberProperty) {
      return Optional.ofNullable(getNumberCondition(comparisonOperator, value, propertyClass, (NumberProperty) property));
    }
    if (property instanceof BooleanProperty) {
      return Optional.ofNullable(getBooleanCondition(comparisonOperator, value, propertyClass, (BooleanProperty) property));
    }
    if (property instanceof InstantProperty) {
      return Optional.ofNullable(getInstantCondition(comparisonOperator, value, propertyClass, (InstantProperty) property));
    }
    if (property instanceof EnumProperty) {
      return Optional.ofNullable(getEquitableCondition(comparisonOperator, value, propertyClass, (EnumProperty) property));
    }

    return Optional.ofNullable(getEquitableCondition(comparisonOperator, value, propertyClass, (StringProperty) property));
  }

  @SneakyThrows
  private Class<?> getPropertyClass(String propertyName) {
    Assert.state(rootType != null, "rootType has not been set");
    Class<?> targetClass = rootType;

    List<String> path = new ArrayList<>(Arrays.asList(propertyName.split("\\.")));

    String fieldName;
    while (!path.isEmpty()) {
      fieldName = path.remove(0);
      Class<?> clazz = targetClass.getDeclaredField(fieldName).getType();
      if (Iterable.class.isAssignableFrom(clazz)) {
        ParameterizedType genericType = (ParameterizedType) targetClass.getDeclaredField(fieldName).getGenericType();
        targetClass = (Class<?>) genericType.getActualTypeArguments()[0];
      } else {
        targetClass = clazz;
      }
    }

    return targetClass;
  }

  private Property getProperty(QBuilder<?> qBuilder, String property, Class<?> fieldType) {
    Property prop;
    if (ClassUtils.isAssignable(Number.class, fieldType)) {
      prop = qBuilder.intNum(property);
    } else if (ClassUtils.isAssignable(Float.class, fieldType) || ClassUtils.isAssignable(Double.class, fieldType)) {
      prop = qBuilder.doubleNum(property);
    } else if (ClassUtils.isAssignable(Boolean.class, fieldType)) {
      prop = qBuilder.bool(property);
    } else if (ClassUtils.isAssignable(String.class, fieldType)) {
      prop = qBuilder.string(property);
    } else if (ClassUtils.isAssignable(Temporal.class, fieldType)) {
      prop = qBuilder.instant(property);
    } else if (ClassUtils.isAssignable(Enum.class, fieldType)) {
      prop = qBuilder.enumeration(property);
    } else {
      throw new IllegalStateException("Unsupported target type: " + fieldType);
    }
    return prop;
  }

  @SuppressWarnings("unchecked")
  private <T extends EquitableProperty & ListableProperty> Condition getEquitableCondition(
      ComparisonOperator comparisonOperator, String value, Class<?> fieldType, T prop) {

    if (comparisonOperator == EQ) {
      return prop.eq(value);
    }
    if (comparisonOperator == NE) {
      return prop.ne(value);
    }
    if (comparisonOperator == IN) {
      return prop.in(value.split(","));
    }
    if (comparisonOperator == NIN) {
      return prop.nin(value.split(","));
    }
    throw new ProgrammingError("Operator '" + comparisonOperator + "' should not have been allowed for type: " + fieldType);
  }

  private Condition getInstantCondition(ComparisonOperator comparisonOperator, String value, Class<?> fieldType, InstantProperty<?> prop) {
    Instant instant = Instant.parse(value);
    if (comparisonOperator == EQ) {
      return prop.eq(instant);
    }
    if (comparisonOperator == NE) {
      return prop.ne(instant);
    }
    if (comparisonOperator == GT || comparisonOperator == GTE) {
      return prop.after(instant, comparisonOperator == GT);
    }
    if (comparisonOperator == LT || comparisonOperator == LTE) {
      return prop.before(instant, comparisonOperator == LT);
    }
    throw new ProgrammingError("Operator '" + comparisonOperator + "' should not have been allowed for type: " + fieldType);
  }

  private Condition getBooleanCondition(ComparisonOperator comparisonOperator, String value, Class<?> fieldType, BooleanProperty prop) {
    boolean booleanValue = Boolean.parseBoolean(value);

    if (comparisonOperator == EQ && booleanValue
        || comparisonOperator == NE && !booleanValue) {
      return prop.isTrue();
    }

    if (comparisonOperator == EQ || comparisonOperator == NE) {
      return prop.isFalse();
    }
    throw new ProgrammingError("Operator '" + comparisonOperator + "' should not have been allowed for type: " + fieldType);
  }

  @SuppressWarnings("unchecked")
  private Condition getNumberCondition(ComparisonOperator comparisonOperator, String value, Class<?> fieldType, NumberProperty prop) {
    if (comparisonOperator == EQ) {
      return prop.eq(Integer.parseInt(value));
    }
    if (comparisonOperator == NE) {
      return prop.ne(Integer.parseInt(value));
    }
    if (comparisonOperator == GT) {
      return prop.gt(Integer.parseInt(value));
    }
    if (comparisonOperator == GTE) {
      return prop.gte(Integer.parseInt(value));
    }
    if (comparisonOperator == LT) {
      return prop.lt(Integer.parseInt(value));
    }
    if (comparisonOperator == LTE) {
      return prop.lte(Integer.parseInt(value));
    }
    if (comparisonOperator == IN) {
      return prop.in(splitInts(value));
    }
    if (comparisonOperator == NIN) {
      return prop.nin(splitInts(value));
    }
    throw new ProgrammingError("Operator '" + comparisonOperator + "' should not have been allowed for type: " + fieldType);
  }
}
