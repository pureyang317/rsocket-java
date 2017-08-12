package io.rsocket.tckdrivers.common;

import com.google.common.base.Throwables;
import com.google.common.io.Files;
import io.rsocket.tckdrivers.server.JavaServerDriver;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.stream.Collectors.toList;

public class TckTestSuite {
  private String suiteName;
  private List<TckClientTest> clientTests = new ArrayList<>();
  private List<String> serverTest;

  public TckTestSuite(String suiteName, List<String> serverTest) {
    this.suiteName = suiteName;
    this.serverTest = serverTest;
  }

  public static List<TckTestSuite> loadAll(File directory) {
    return Arrays.stream(directory.listFiles())
        .filter(f -> f.getName().startsWith("server"))
        .map(f -> TckTestSuite.extractTests(f))
        .collect(toList());
  }

  public List<TckClientTest> clientTests() {
    return clientTests;
  }

  private static TckTestSuite extractTests(File serverFile) {
    try {
      List<String> testLines = Files.readLines(serverFile, StandardCharsets.UTF_8);
      String name = serverFile.getName().replaceAll("^server", "").replaceAll(".txt$", "");

      TckTestSuite tckTestSuite = new TckTestSuite(name, testLines);

      tckTestSuite.clientTests =
          TckClientTest.extractTests(
              new File(serverFile.getParentFile(), "client" + name + ".txt"));

      return tckTestSuite;
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  @Override public String toString() {
    return suiteName;
  }

  public JavaServerDriver driver() {
    JavaServerDriver javaServerDriver = new JavaServerDriver();
    javaServerDriver.parse(serverTest);
    return javaServerDriver;
  }
}