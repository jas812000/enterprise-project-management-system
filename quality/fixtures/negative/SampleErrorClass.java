

package com.jamesstevens.epms.Sample;

import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * REUSABLE NEGATIVE-TEST FIXTURE.
 *
 * Deliberately contains coding defects to validate
 * Checkstyle, PMD, and SpotBugs quality gates.
 *
 * This fixture is shared by local and CI quality-gate tests.
 *
 * It must remain outside production source directories
 * and must never be included in production builds or deployments.
 *
 * All credential-looking values are fake test values.
 */
public class SampleErrorClass {

    // Intentionally fake credentials.
    private static final String PASSWORD = "FAKE_PASSWORD_DO_NOT_USE_123";
    private static final String API_KEY = "FAKE_API_KEY_FOR_TESTING_ONLY";
    private static final String SECRET_TOKEN = "FAKE_SECRET_TOKEN_FOR_TESTING";
    private static final String DB_PASSWORD = "FAKE_DATABASE_PASSWORD";

    // Naming, visibility, unused-field, and magic-number defects.
    public int BAD_FIELD_NAME=10;
    private String unusedField = "unused";
    private int anotherUnusedField = 123;
    public static Map<String, String> GLOBAL_DATA = new HashMap<>();

    private final List<String> internalList = new ArrayList<>();
    private final byte[] internalBytes = new byte[10];

    // Mutable static state and thread-safety defects.
    private static int sharedCounter = 0;
    private static final Object LOCK = new Object();

    public void BadMethodName(){
        int x=10;
        int y=20;
        System.out.println(x+y);
    }

    // Empty catch block.
    public void emptyCatchTest() {
        try {
            throw new IllegalArgumentException("Test");
        } catch (IllegalArgumentException exception) {
        }
    }

    // Definite null dereference.
    public int nullPointerTest() {
        String value = null;
        return value.length();
    }

    // Incorrect reference comparison.
    public boolean stringComparisonTest(String input) {
        return input == "admin";
    }

    // Ignored immutable-object return value.
    public void ignoredReturnValueTest() {
        String value = "hello";
        value.trim();
    }

    // File resource is not closed.
    public int resourceLeakTest() throws IOException {
        FileInputStream stream =
                new FileInputStream("nonexistent-test-file.txt");
        return stream.read();
    }

    // Predictable random values are inappropriate for security tokens.
    public int insecureRandomTest() {
        Random random = new Random();
        return random.nextInt(1000000);
    }

    // SQL injection pattern and unclosed JDBC resources.
    public void sqlInjectionTest(String username) throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/fake_test_db",
                "fake_test_user",
                DB_PASSWORD
        );

        Statement statement = connection.createStatement();

        String query =
                "SELECT * FROM users WHERE username = '" + username + "'";

        statement.executeQuery(query);
    }

    // Logging credential-looking values.
    public void printSecretsTest() {
        System.out.println(PASSWORD);
        System.out.println(API_KEY);
        System.out.println(SECRET_TOKEN);
    }

    // Empty control statement.
    public void emptyIfTest(boolean enabled) {
        if (enabled) {
        }
    }

    // Unnecessary object creation.
    public String unnecessaryObjectTest() {
        return new String("unnecessary");
    }

    // Unused method parameter.
    public void unusedParameterTest(String unusedParameter) {
        System.out.println("Unused parameter");
    }

    // Deliberately poor formatting and excessive line length.
    public void formattingTest(){int a=1;int b=2;int c=3;System.out.println("This deliberately long statement tests whitespace, line length, magic numbers, and formatting violations across the quality gate: "+a+b+c);}

    // Exposes mutable internal state.
    public List<String> exposeInternalState() {
        return internalList;
    }

    // Exposes mutable internal array.
    public byte[] exposeInternalArray() {
        return internalBytes;
    }

    // Directly stores an externally supplied mutable array.
    public void storeExternalArray(byte[] input) {
        for (int index = 0; index < input.length; index++) {
            internalBytes[index % internalBytes.length] = input[index];
        }
    }

    // Incorrect equals implementation.
    @Override
    public boolean equals(Object other) {
        return other instanceof SampleErrorClass;
    }

    // Deliberately missing hashCode override.

    // Incorrect synchronization on a mutable field.
    public void incrementCounter() {
        sharedCounter++;
    }

    // Synchronization using a shared public lock is discouraged.
    public void unsafeSynchronization() {
        synchronized (SampleErrorClass.class) {
            sharedCounter++;
        }
    }

    // Wait without a condition loop.
    public void incorrectWait() throws InterruptedException {
        synchronized (LOCK) {
            LOCK.wait(100);
        }
    }

    // Thread interruption is swallowed.
    public void ignoredInterruption() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            System.out.println("Interrupted");
        }
    }

    // Incorrect comparison involving NaN.
    public boolean incorrectNanComparison(double value) {
        return value == Double.NaN;
    }

    // Integer division followed by floating-point conversion.
    public double integerDivisionTest(int numerator, int denominator) {
        return (double) (numerator / denominator);
    }

    // Potential division by zero.
    public int divisionByZeroTest(int input) {
        int divisor = input - input;
        return 100 / divisor;
    }

    // Inappropriate use of System.exit in application code.
    public void terminateApplication() {
        System.exit(1);
    }
}
