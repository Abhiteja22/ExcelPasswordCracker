import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public class Main {
    public static void main(String[] args) {
        String fileToCrack = (args.length > 0 && args[0] != null) ? args[0] : "input.xlsx";
        final File inputFile = new File(fileToCrack);
        String crackedPassword = crackPassword(inputFile);
        System.out.println("Password found: " + crackedPassword);
    }

    public static String crackPassword(File inputFile) {
        Character[] charSet = crackingCharacterSet();

        // Parameters
        final int minPasswordLength = 6;
        final int maxPasswordLength = Integer.MAX_VALUE;

        // Statistics tracking
        final AtomicLong passwordsTested = new AtomicLong(0);
        final Instant startTime = Instant.now();

        // Thread pool
        int threadCount = Runtime.getRuntime().availableProcessors();
        ExecutorService threadPoolExecutor = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CompletableFuture<String> cf = new CompletableFuture<>();
        BlockingQueue<String> passwordQueue = new LinkedBlockingQueue<>(threadCount * 4);

        // Runnables to execute
        final Decryptor excelDecryptor = getDecryptor(inputFile);
        Runnable producer = passwordProvider(charSet, passwordQueue, minPasswordLength, maxPasswordLength);
        Runnable consumer = passwordCracker(cf, passwordQueue, excelDecryptor, passwordsTested);

        executeRunnableDesiredTimes(1, threadPoolExecutor, producer);
        executeRunnableDesiredTimes(threadCount - 1, threadPoolExecutor, consumer);

        String result = crackPassword(threadPoolExecutor, cf, passwordsTested, startTime);
        return result;
    }

    private static Character[] crackingCharacterSet() {
        // Character set for cracking
        ArrayList<Character> characters = new ArrayList<>();
        // Lower case
        IntStream.range(97, 123).forEach(i -> characters.add((char) i));
        // Upper case
        IntStream.range(65, 91).forEach(i -> characters.add((char) i));
        // Numbers
        IntStream.range(48, 58).forEach(i -> characters.add((char) i));

        Character[] charSet = getCharSet(characters);
        return charSet;
    }

    private static String crackPassword(ExecutorService service, CompletableFuture<String> cf, AtomicLong passwordsTested, Instant startTime) {
        String result = "";
        try {
            result = cf.get();
            Instant finish = Instant.now();
            long timeElapsed = Duration.between(startTime, finish).toSeconds();
            long passwordsTestedCount = passwordsTested.get();
            double passwordsPerSecond = (double) passwordsTestedCount / Math.max(1, timeElapsed);
            
            System.out.println("\n========== RESULTS ==========");
            System.out.println("Password found: " + result);
            System.out.println("Total time elapsed: " + timeElapsed + "s");
            System.out.println("Passwords tested: " + passwordsTestedCount);
            System.out.println("Passwords per second: " + String.format("%.2f", passwordsPerSecond));
            System.out.println("=============================\n");
            
            service.shutdownNow();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        return result;
    }

    private static void executeRunnableDesiredTimes(int times, ExecutorService service, Runnable runnable) {
        for (int i = 0; i < times; ++i) {
            service.execute(runnable);
        }
    }

    private static Runnable passwordProvider(Character[] charSet, BlockingQueue<String> passwordQueue, int minLen, int maxLen) {
        return () -> {
            try {
                // 1. Dictionary attack with common passwords
                System.out.println("[INFO] Starting dictionary attack...");
                List<String> dictionaryAttack = generateDictionaryAttack();
                
                for (String password : dictionaryAttack) {
                    if (Thread.interrupted()) break;
                    passwordQueue.offer(password, 12, TimeUnit.HOURS);
                }
                
                // 2. Random sequence generation with no repeating characters (length 6-10)
                System.out.println("[INFO] Dictionary attack completed. Starting random sequence generation (length 6-10, no repeating chars)...");
                generateRandomSequences(passwordQueue, 10000000); // Generate 10 million random passwords
                
                // 3. If all else fails, fall back to limited brute force
                System.out.println("[INFO] Random sequences completed. Starting brute force fallback (length 4-6)...");
                bruteForcePasswords(charSet, passwordQueue, minLen, Math.min(maxLen, 6));
                
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static void generateRandomSequences(BlockingQueue<String> passwordQueue, int count) throws InterruptedException {
        Random random = new Random();
        
        // Character sets
        String uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lowercase = "abcdefghijklmnopqrstuvwxyz";
        String numbers = "0123456789";
        String specialChars = "!@#$%^&*()_+-=[]{}|;:,.<>?";
        
        for (int i = 0; i < count; i++) {
            if (Thread.interrupted()) break;
            
            // Random length between 6-10
            int length = 6 + random.nextInt(5); // 6, 7, 8, 9, or 10
            
            // Determine how many special characters to include (0, 1, or 2 max)
            int numSpecialChars = random.nextInt(3); // 0, 1, or 2
            numSpecialChars = Math.min(numSpecialChars, length - 1); // Ensure we have room for other chars
            
            // Build available characters list
            List<Character> availableChars = new ArrayList<>();
            
            // Add uppercase letters
            for (char c : uppercase.toCharArray()) {
                availableChars.add(c);
            }
            // Add lowercase letters
            for (char c : lowercase.toCharArray()) {
                availableChars.add(c);
            }
            // Add numbers
            for (char c : numbers.toCharArray()) {
                availableChars.add(c);
            }
            // Add special characters (limited)
            int specialCharsToAdd = numSpecialChars;
            for (char c : specialChars.toCharArray()) {
                if (specialCharsToAdd > 0) {
                    availableChars.add(c);
                    specialCharsToAdd--;
                } else {
                    break;
                }
            }
            
            // Generate random sequence with no repeating characters
            StringBuilder password = new StringBuilder();
            List<Character> charsToUse = new ArrayList<>(availableChars);
            
            for (int j = 0; j < length && charsToUse.size() > 0; j++) {
                int randomIndex = random.nextInt(charsToUse.size());
                password.append(charsToUse.get(randomIndex));
                charsToUse.remove(randomIndex);
            }
            
            if (password.length() == length) {
                passwordQueue.offer(password.toString(), 12, TimeUnit.HOURS);
            }
        }
    }

    private static List<String> generateDictionaryAttack() {
        List<String> passwords = new ArrayList<>();
        
        // Company names and test-related keywords
        List<String> companies = Arrays.asList(
            "Infosys", "BlueOcean", "Blue", "Ocean", "Systems", "infosys", "blueoceansystems", "agentic", "agenticAI", "AI", "AIagents", "AIsoftwareengineer"
        );
        
        // Common base words (targeted for business/test context)
        List<String> baseWords = Arrays.asList(
            "password", "Password", "PASSWORD",
            "admin", "Admin", "ADMIN",
            "test", "Test", "TEST",
            "user", "User", "USER",
            "welcome", "Welcome", "WELCOME",
            "login", "Login", "LOGIN",
            "access", "Access", "ACCESS",
            "secure", "Secure", "SECURE",
            "excel", "Excel", "EXCEL",
            "qa", "QA", "Qa",
            "dev", "Dev", "DEV"
        );
        
        // Common number combinations
        List<String> numberSuffixes = Arrays.asList(
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "00", "01", "23", "24", "25",
            "123", "1234", "12345",
            "2023", "2024", "2025", "2026",
            "111", "222", "333", "444", "555", "666", "777", "888", "999",
            "000", "007", "101", "111", "123", "456", "789", "999"
        );
        
        // Special character options
        List<String> specialChars = Arrays.asList(
            "", "!", "@", "#", "$", "!", "!@", "@#", "#$", "!@#", "@#$", "#$%"
        );
        
        // 1. Add Password variations with numbers (like Password123, Passw0rd123, etc.)
        List<String> passwordVariations = Arrays.asList(
            "Password", "Passw0rd", "Passw@rd", "Pass123", "Pass@123", "Pass!123"
        );
        
        for (String pass : passwordVariations) {
            for (String num : numberSuffixes) {
                passwords.add(pass + num);
            }
            // With special chars
            passwords.add(pass + "!");
            passwords.add(pass + "@");
            passwords.add(pass + "#");
            passwords.add(pass + "123!");
            passwords.add(pass + "@123");
            passwords.add(pass + "#2024");
        }
        
        // 2. Company name combinations
        for (String company : companies) {
            passwords.add(company);
            passwords.add(company.toUpperCase());
            passwords.add(capitalize(company));
            
            for (String num : numberSuffixes) {
                passwords.add(company + num);
                passwords.add(capitalize(company) + num);
            }
            
            for (String special : specialChars) {
                if (!special.isEmpty()) {
                    passwords.add(company + special);
                    passwords.add(company + special + "123");
                    passwords.add(capitalize(company) + special + "2024");
                }
            }
        }
        
        // 3. Company + Test/QA variations
        for (String company : companies) {
            passwords.add(company + "Test");
            passwords.add(company + "Test123");
            passwords.add(company + "Test@123");
            passwords.add(company + "QA");
            passwords.add(company + "QA123");
            passwords.add(company + "2024");
            passwords.add(company + "@2024");
            passwords.add(company + "123!");
        }
        
        // 4. Base word combinations
        for (String base : baseWords) {
            passwords.add(base);
            
            for (String num : numberSuffixes) {
                if (!num.isEmpty()) {
                    passwords.add(base + num);
                }
            }
            
            for (String special : specialChars) {
                if (!special.isEmpty()) {
                    passwords.add(base + special);
                    passwords.add(base + special + "1");
                    passwords.add(base + special + "123");
                    passwords.add(base + special + "2024");
                }
            }
        }
        
        // 5. Common test passwords (high priority)
        List<String> commonTestPasswords = Arrays.asList(
            "Test@123", "Test@2024", "Test123",
            "Admin@123", "Admin123", "admin123",
            "Password123", "Password@123", "Password!123",
            "Passw0rd123", "Passw@rd123",
            "Welcome@2024", "Welcome123",
            "Secure#2024", "Secure123",
            "BlueOcean@2024", "BlueOcean123",
            "Infosys@123", "Infosys123", "infosys123",
            "Systems@2024", "Systems123",
            "QA@Test123", "QATest123", "qa123",
            "Dev@Test2024", "DevTest123", "dev123",
            "Qa!Test123", "QATest@123",
            "Test!Pass123", "TestPass123"
        );
        passwords.addAll(commonTestPasswords);
        
        // 6. Excel/Sheet specific (since it's an Excel file)
        List<String> excelPasswords = Arrays.asList(
            "Excel123", "Excel@123", "Excel2024",
            "Sheet123", "Sheet@2024",
            "Document123", "Document@2024",
            "Report123", "Report@2024"
        );
        passwords.addAll(excelPasswords);
        
        // Remove duplicates while preserving order
        LinkedHashSet<String> uniquePasswords = new LinkedHashSet<>(passwords);
        
        return new ArrayList<>(uniquePasswords);
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    private static void bruteForcePasswords(Character[] charSet, BlockingQueue<String> passwordQueue, int minLen, int maxLen) throws InterruptedException {
        for (int i = minLen; i <= maxLen; ++i) {
            generateAndQueuePasswords(charSet, passwordQueue, i, "", charSet.length);
        }
    }

    private static void generateAndQueuePasswords(Character[] charSet, BlockingQueue<String> passwordQueue, 
                                                   int length, String prefix, int charSetSize) throws InterruptedException {
        if (length == 0) {
            passwordQueue.offer(prefix, 12, TimeUnit.HOURS);
            return;
        }
        for (Character c : charSet) {
            generateAndQueuePasswords(charSet, passwordQueue, length - 1, prefix + c, charSetSize);
        }
    }

    private static Runnable passwordCracker(CompletableFuture<String> cf, BlockingQueue<String> passwordQueue, Decryptor excelDecryptor, AtomicLong passwordsTested) {
        return () -> {
            while (!Thread.interrupted()) {
                try {
                    String password = passwordQueue.take();
                    passwordsTested.incrementAndGet();
                    print("Testing password: " + password);
                    boolean decryptResult = excelDecryptor.verifyPassword(password);
                    if (decryptResult) {
                        cf.complete(password);
                        break;
                    }
                } catch (InterruptedException | GeneralSecurityException ignored) {
                    break;
                }
            }
        };
    }

    private static Decryptor getDecryptor(File inputFile) {
        POIFSFileSystem fileSystem;
        EncryptionInfo info;
        Decryptor decryptor = null;

        try {
            fileSystem = new POIFSFileSystem(inputFile);
            info = new EncryptionInfo(fileSystem);
            decryptor = Decryptor.getInstance(info);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Decryptor finalDecryptor = decryptor;
        return finalDecryptor;
    }

    private static Character[] getCharSet(ArrayList<Character> letters) {
        Character[] chars = new Character[letters.size()];
        for (int i = 0; i < chars.length; ++i) {
            chars[i] = letters.get(i);
        }
        return chars;
    }

    static void print(Object output) {
        String formattedTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSSSSSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
        System.out.printf("%s: %ss - %s%n", formattedTime, Thread.currentThread().getName(), output);
    }
}
