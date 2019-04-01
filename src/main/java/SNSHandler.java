import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClientBuilder;
import com.amazonaws.services.simpleemail.model.*;

import java.text.SimpleDateFormat;
import java.util.*;

public class SNSHandler implements RequestHandler<SNSEvent,Context>{

    public Context handleRequest(SNSEvent request, Context context){
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Calendar.getInstance().getTime());

        context.getLogger().log("RequestHandler started: " + timeStamp);

        String mailAddress =  request.getRecords().get(0).getSNS().getMessage();

        context.getLogger().log("Message from SNS:" + mailAddress);

        AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();

        //minutes of TTL
        long TTL = (long)Integer.parseInt(System.getenv("timeToLive"));
        String FROM = System.getenv("FROM");
        String source = "no-reply@" + FROM;

        AmazonDynamoDB clientDB =
                AmazonDynamoDBClientBuilder.standard()
                    .withRegion(Regions.US_EAST_1)
                    .withCredentials(awsCredentialsProvider)
                    .build();

        DynamoDB dynamoDB = new DynamoDB(clientDB);

        Table table = dynamoDB.getTable("csye6225");

        //query DB by Email (not expire)
        long curTime = System.currentTimeMillis()  / 1000L;
        Map<String, Object> expressionAttributeValues = new HashMap<String, Object>();
        expressionAttributeValues.put(":Email", mailAddress);
        expressionAttributeValues.put(":curTime", curTime);

        ItemCollection<ScanOutcome> items = table.scan (
                "Email = :Email and ExpireTime >= :curTime",
                "Id",
                null,
                expressionAttributeValues);

        Iterator<Item> iterator = items.iterator();

        //if not found this entry, sent email
        if(!iterator.hasNext()) {
            String uuid = UUID.randomUUID().toString();

            //write a new entry to DB with new token
            //Id (token) for hash key, Email for range key, attributes are TTL
            long expireTime = (System.currentTimeMillis() + 60*1000*TTL) / 1000L;
            try {
                table.putItem(new Item()
                        .withPrimaryKey("Id", uuid,"Email",mailAddress)
                        .withLong("ExpireTime", expireTime));
                context.getLogger().log("DB updated success.");
            }
            catch (Exception ex) {
                context.getLogger().log("DB updated fail.");
                context.getLogger().log(ex.getMessage());
            }

            //send mail
            String SUBJECT = "Password Reset Service";
            String TEXTBODY = "http://" + FROM + "/reset?email=" + mailAddress + "&token=" + uuid;

            try {

                AmazonSimpleEmailService client =
                        AmazonSimpleEmailServiceClientBuilder.standard()
                                .withRegion(Regions.US_EAST_1)
                                .withCredentials(awsCredentialsProvider)
                                .build();

                SendEmailRequest email = new SendEmailRequest()
                        .withDestination(
                                new Destination().withToAddresses(mailAddress))
                        .withMessage(new Message()
                                .withBody(new Body()
                                        .withText(new Content()
                                                .withCharset("UTF-8").withData(TEXTBODY)))
                                .withSubject(new Content()
                                        .withCharset("UTF-8").withData(SUBJECT)))
                        .withSource(source);
                client.sendEmail(email);

                context.getLogger().log("Email sent!");
            } catch (Exception ex) {
                context.getLogger().log("Email sent failed");
                context.getLogger().log(ex.getMessage());
            }
        }else context.getLogger().log("request within time frame, ignored");

        timeStamp = new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(Calendar.getInstance().getTime());
        context.getLogger().log("RequestHandler finished: " + timeStamp);

        return null;
    }

}
