//////////////////////////////////////////////////////////////////////////////
// Part of the Agnos RPC Framework
//    http://agnos.sourceforge.net
//
// Copyright 2011, International Business Machines Corp.
//                 Author: Tomer Filiba (tomerf@il.ibm.com)
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//////////////////////////////////////////////////////////////////////////////

using System;
using System.Collections.Generic;


namespace Agnos.Utils
{
	/// <summary>
	/// A class that acts like a dictionary with weakref'ed keys, meaning,
	/// the entry will be removed once its key is garbage-collected.
	/// required by ObjectIDGenerator
	/// </summary>
	internal class WeakKeyDict<TKey, TValue>
	{
		private struct Pair
		{
			public WeakReference wkey;
            public TValue val;
			
			public Pair(TKey k, TValue v)
			{
				wkey = new WeakReference(k);
				val = v;
			}
			
			public bool IsAlive()
			{
				return wkey.IsAlive;
			}
			public bool IsEqual(TKey key)
			{
				return wkey.Target != null && wkey.Target.Equals(key);
			}
		}
		
		private Dictionary<int, List<Pair>> dict;
		
		public WeakKeyDict(): this(8000) 
		{
		}
		public WeakKeyDict(int capacity)
		{
			dict = new Dictionary<int, List<Pair>>(capacity);
		}

		public void Add(TKey key, TValue val)
		{
			int hash = key.GetHashCode();
			if (!dict.ContainsKey(hash)) {
				List<Pair> buckets = new List<Pair>();
				buckets.Add(new Pair(key, val));
				dict.Add(hash, buckets);
			}
			else {
				List<Pair> buckets = dict[hash];
				bool found = false;
				for (int i = buckets.Count - 1; i >= 0; i--) {
					Pair p = buckets[i];
					if (!p.IsAlive()) {
						buckets.RemoveAt(i);
					}
					else if (p.IsEqual(key)) {
						found = true;
						p.val = val;
					}
				}
				if (!found) {
					buckets.Add(new Pair(key, val));
				}
			}
		}
		
		public bool TryGetValue(TKey key, out TValue val)
		{
			int hash = key.GetHashCode();
			List<Pair> buckets;
			if (dict.TryGetValue(hash, out buckets)) {
				for (int i = buckets.Count - 1; i >= 0; i--) {
					Pair p = buckets[i];
					if (!p.IsAlive()) {
						buckets.RemoveAt(i);
					}
					else if (p.IsEqual(key)) {
						val = p.val;
						return true;
					}
				}
			}
            val = default(TValue);
			return false;
		}
		
		public void Compact()
		{
			List<int> dead_keys = new List<int>();
			foreach (KeyValuePair<int, List<Pair>> item in dict) {
				for (int i = item.Value.Count - 1; i >= 0; i--) {
					Pair p = item.Value[i];
					if (!p.IsAlive()) {
						item.Value.RemoveAt(i);
					}
				}
				if (item.Value.Count < 1) {
					dead_keys.Add(item.Key);
				}
			}
			foreach (int k in dead_keys) {
				dict.Remove(k);
			}
		}
	}

	/// <summary>
	/// a class that generates a unique identifier for every object
	/// </summary>
	internal sealed class ObjectIDGenerator {
        private readonly WeakKeyDict<Object, long> dict;
		private long counter;

		public ObjectIDGenerator() {
			dict = new WeakKeyDict<Object, long>();
			counter = 0;
		}

		public long getID(Object obj) {
			long id;
			lock (this) {
				if (dict.TryGetValue(obj, out id)) {
					return id;
				} 
				else {
					counter += 1;
					dict.Add(obj, counter);
					return counter;
				}
			}
		}
		
		public void Compact()
		{
			dict.Compact();
		}
	}
}
