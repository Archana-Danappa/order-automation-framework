package framework.reporting;

import org.testng.IAnnotationTransformer;
import org.testng.annotations.ITestAnnotation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Registered in testng.xml. Applies TransientFailureRetryAnalyzer to every
 * @Test automatically, so individual test authors never need to remember
 * retryAnalyzer = ... on each method.
 */
public class RetryAnnotationTransformer implements IAnnotationTransformer {

    @Override
    public void transform(ITestAnnotation annotation, Class testClass, Constructor testConstructor, Method testMethod) {
        annotation.setRetryAnalyzer(TransientFailureRetryAnalyzer.class);
    }
}
