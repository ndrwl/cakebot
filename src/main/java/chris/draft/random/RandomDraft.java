/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package chris.draft.random;
import sx.blah.discord.handle.obj.IMessage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
/**
 *
 * @author JCH
 */

public class RandomDraft {
    private final String pathToCivFile = "src/main/resources/civilizations.txt";
    private ArrayList<String> civs;
    private final int DEFAULT_SIZE = 12;
    
    RandomDraft()
    {
        Load(pathToCivFile);
    }
    
    RandomDraft(String pathToFile)
    {
        Load(pathToFile);
    }
    
    public void Load(String pathToFile)
    {
        try
        {
            File file = new File(pathToFile);
            FileReader fileReader = new FileReader(file);
            BufferedReader bufferedReader = new BufferedReader(fileReader);
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                civs.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } 
    }
    
    public void Draft(boolean isAdmin, List<String> arguments, IMessage message) {
        
        if(arguments.size() != 2)
            return;
        
        Integer amountToDraft = Integer.parseInt(arguments.get(1));
        
        amountToDraft = (amountToDraft < 0 ) ? amountToDraft = DEFAULT_SIZE : amountToDraft;
        amountToDraft = (amountToDraft > civs.size()) ? amountToDraft = DEFAULT_SIZE : amountToDraft;
        
        ArrayList<String> temp = civs;
        Collections.shuffle(temp); //Shuffle our list
        StringBuilder stringBuilder = new StringBuilder();
        for(int i = 0; i < amountToDraft; i++)
        {
            stringBuilder.append(temp.get(i));
            if(i != amountToDraft-1)
            {
                stringBuilder.append(",");
            } 
        }
        
        message.reply(stringBuilder.toString());
    }
}
